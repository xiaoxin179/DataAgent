/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/**
 * 动态模型工厂。
 *
 * 这个类负责把数据库中的“模型配置数据”转成真正可调用的 Spring AI 模型实例。
 * 在当前项目里，它刻意统一使用 OpenAI 协议适配层：
 * - 不同厂商通过 `baseUrl` 和路径差异适配
 * - 上层业务仍然只看到统一的 ChatModel / EmbeddingModel 抽象
 *
 * 这样做的好处：
 * 1. 减少多厂商 SDK 分支代码。
 * 2. 让自定义兼容 OpenAI 协议的模型服务也能被接入。
 * 3. 把代理、鉴权、路径定制都集中收敛在一个工厂里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicModelFactory {

	/**
	 * 创建聊天模型。
	 *
	 * 实现步骤：
	 * 1. 校验基础配置完整性。
	 * 2. 构造 `OpenAiApi`，它是底层 HTTP 通信入口。
	 * 3. 构造 `OpenAiChatOptions`，写入默认模型名、温度、最大 token 等运行选项。
	 * 4. 返回 `OpenAiChatModel`。
	 *
	 * 关键框架 API：
	 * - `OpenAiApi`：Spring AI 中基于 OpenAI 协议的底层客户端。
	 * - `OpenAiChatModel`：真正被业务调用的聊天模型实现。
	 */
	public ChatModel createChatModel(ModelConfigDTO config) {

		// 日志只输出定位信息，不输出 apiKey 等敏感配置。
		log.info("Creating NEW ChatModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		// 在创建 HTTP 客户端前验证必填字段，避免得到更难理解的底层异常。
		checkBasic(config);

		// custom 提供商可以不需要密钥；OpenAiApi Builder 仍要求传入非 null 字符串。
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		// 同时配置同步 RestClient 和异步 WebClient，让普通调用与流式调用使用一致的代理设置。
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		// 兼容不使用 OpenAI 默认 /v1/chat/completions 路径的第三方服务。
		if (StringUtils.hasText(config.getCompletionsPath())) {
			apiBuilder.completionsPath(config.getCompletionsPath());
		}
		// 到这里才生成不可变的底层 API 客户端。
		OpenAiApi openAiApi = apiBuilder.build();

		// 把数据库中的推理参数设置成该 ChatModel 每次调用的默认值。
		OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
			.model(config.getModelName())
			.temperature(config.getTemperature())
			.maxTokens(config.getMaxTokens())
			.streamUsage(true)
			.build();

		// Registry 会再把 ChatModel 包装成更便于业务调用的 ChatClient。
		return OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(openAiChatOptions).build();
	}

	/**
	 * 创建 Embedding 模型。
	 *
	 * 逻辑和 ChatModel 类似，只是目标实现换成了 `OpenAiEmbeddingModel`。
	 */
	public EmbeddingModel createEmbeddingModel(ModelConfigDTO config) {
		// 记录本次动态创建所使用的提供商和模型，方便排查热切换问题。
		log.info("Creating NEW EmbeddingModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		// Embedding 与 Chat 共用相同的基础字段校验规则。
		checkBasic(config);

		// 本地 custom 服务允许空密钥，因此统一归一化为空字符串。
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		// OpenAiApi 负责协议层通信，模型对象负责更上层的向量接口。
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		// 支持提供商自定义 Embedding 请求路径。
		if (StringUtils.hasText(config.getEmbeddingsPath())) {
			apiBuilder.embeddingsPath(config.getEmbeddingsPath());
		}

		// 构建共用的 OpenAI 协议客户端。
		OpenAiApi openAiApi = apiBuilder.build();
		// MetadataMode.EMBED 表示按 Embedding 场景处理文档元数据，并复用 Spring AI 默认重试模板。
		return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * 基础配置校验。
	 *
	 * 特殊规则：
	 * - provider 不是 `custom` 时，要求必须有 apiKey
	 * - `custom` 模式允许某些本地兼容服务没有真实 API Key
	 */
	private static void checkBasic(ModelConfigDTO config) {
		// 所有请求都需要明确的服务根地址。
		Assert.hasText(config.getBaseUrl(), "baseUrl must not be empty");
		// 标准云提供商必须鉴权，只有用户自建的 custom 服务允许省略 apiKey。
		if (!"custom".equalsIgnoreCase(config.getProvider())) {
			Assert.hasText(config.getApiKey(), "apiKey must not be empty");
		}
		// 同一服务可能托管多个模型，因此模型名称也是必填项。
		Assert.hasText(config.getModelName(), "modelName must not be empty");
	}

	/**
	 * 构造同步 HTTP 调用使用的 RestClient.Builder，并在需要时接入代理。
	 *
	 * 为什么同步和异步要分开：
	 * - Spring AI 内部既可能走同步调用，也可能走 WebFlux 异步调用
	 * - 两套客户端使用的底层 HTTP 库不同，所以代理配置也要分别装配
	 */
	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config) {
		// 未启用代理时使用 Spring 默认同步客户端，不引入额外网络层。
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			return RestClient.builder();
		}

		log.info("[Proxy-Init] Model [{}] is using SYNC proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
				config.getProxyPort());

		// Apache HttpClient 通过 CredentialsProvider 保存可选的代理认证信息。
		BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
		// 只有配置了用户名时才启用代理 Basic Auth。
		if (StringUtils.hasText(config.getProxyUsername())) {
			log.info("[Proxy-Auth] Enabling Basic Auth for SYNC proxy, user: {}", config.getProxyUsername());
			credsProvider.setCredentials(new AuthScope(config.getProxyHost(), config.getProxyPort()),
					new UsernamePasswordCredentials(config.getProxyUsername(),
							config.getProxyPassword().toCharArray()));
		}

		// 创建绑定代理地址和认证信息的 Apache 同步 HTTP 客户端。
		CloseableHttpClient httpClient = HttpClients.custom()
			.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()))
			.setDefaultCredentialsProvider(credsProvider)
			.build();

		// 将 Apache 客户端桥接到 Spring RestClient。
		return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
	}

	/**
	 * 构造流式异步调用使用的 WebClient.Builder，并在需要时配置 Netty HTTP 代理。
	 */
	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config) {
		// 未启用代理时直接返回标准 WebClient Builder。
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			return WebClient.builder();
		}

		log.info("[Proxy-Init] Model [{}] is using ASYNC (Netty) proxy -> {}:{}", config.getModelName(),
				config.getProxyHost(), config.getProxyPort());

		// 流式响应可能持续较久，因此给 Netty 客户端设置三分钟响应超时。
		HttpClient nettyClient = HttpClient.create().responseTimeout(java.time.Duration.ofMinutes(3)).proxy(p -> {
			// 声明 HTTP 代理类型、主机和端口。
			ProxyProvider.Builder proxyBuilder = p.type(ProxyProvider.Proxy.HTTP)
				.host(config.getProxyHost())
				.port(config.getProxyPort());

			// Netty 的代理认证通过 username/password 回调配置。
			if (StringUtils.hasText(config.getProxyUsername())) {
				log.info("[Proxy-Auth] Enabling Basic Auth for ASYNC proxy, user: {}", config.getProxyUsername());
				proxyBuilder.username(config.getProxyUsername()).password(s -> config.getProxyPassword());
			}
		});

		// ReactorClientHttpConnector 把定制的 Netty HttpClient 接入 Spring WebClient。
		return WebClient.builder().clientConnector(new ReactorClientHttpConnector(nettyClient));
	}

}
