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
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * 当前激活 AI 模型的注册中心。
 *
 * 它的职责不是持久化模型配置，而是做“运行时读缓存 + 懒加载实例化”：
 * - Chat 链路从这里拿 `ChatClient`
 * - Embedding 链路从这里拿 `EmbeddingModel`
 *
 * 为什么需要这个层：
 * 1. 数据库存的是配置，不是可直接调用的模型实例。
 * 2. 模型实例化通常比较重，不适合每次请求都重新创建。
 * 3. 项目支持热切换模型，所以需要一个可刷新缓存的统一入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelRegistry {

	private final DynamicModelFactory modelFactory;

	private final ModelConfigDataService modelConfigDataService;

	/**
	 * volatile 保证不同线程都能看到最新缓存引用。
	 * 配合双重检查锁，既降低并发开销，也避免重复初始化。
	 */
	private volatile ChatClient currentChatClient;

	private volatile EmbeddingModel currentEmbeddingModel;

	/**
	 * 获取当前激活的 ChatClient。
	 *
	 * 核心策略：
	 * - 懒加载：第一次真正用到时才初始化
	 * - 缓存：后续请求直接复用
	 * - 热刷新：调用 `refreshChat()` 后，下次访问会按最新配置重新初始化
	 *
	 * 这里返回的是 `ChatClient` 而不是 `ChatModel`，是因为业务层通常更关心“怎么对话调用”，
	 * 而不是底层模型细节。
	 */
	public ChatClient getChatClient() {
		// 第一次无锁检查：缓存已经存在时直接返回，避免每次模型调用都进入 synchronized。
		if (currentChatClient == null) {
			// 初始化过程必须串行，防止多个请求同时创建多个模型客户端。
			synchronized (this) {
				// 获得锁后再次检查，因为等待锁期间可能已有其他线程完成初始化。
				if (currentChatClient == null) {
					log.info("Initializing global ChatClient...");
					try {
						// 从数据库读取当前启用的 CHAT 类型配置。
						ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
						// 没有启用配置时暂不创建，后面的统一检查会给出明确错误。
						if (config != null) {
							// 工厂把数据库配置转换成真正可调用的 ChatModel。
							ChatModel chatModel = modelFactory.createChatModel(config);
							// 业务层统一通过 ChatClient 使用模型，因此在这里再包装一层。
							currentChatClient = ChatClient.builder(chatModel).build();
						}
					}
					catch (Exception e) {
						// 初始化异常先完整记录，随后由 null 检查转换为面向配置的错误提示。
						log.error("Failed to initialize ChatClient: {}", e.getMessage(), e);
					}

					// 无配置或创建失败都不能继续执行聊天请求。
					if (currentChatClient == null) {
						throw new RuntimeException(
								"No active CHAT model configured. Please configure it in the dashboard.");
					}
				}
			}
		}
		// 返回 volatile 缓存，后续请求会复用同一个线程安全客户端。
		return currentChatClient;
	}

	/**
	 * 获取当前激活的 EmbeddingModel。
	 *
	 * 和 ChatClient 类似，这里同样是懒加载 + 缓存。
	 * 但 Embedding 链路多了一层特别处理：当真的没有可用配置时，返回 Dummy 模型而不是直接 null。
	 *
	 * 原因：
	 * - 一些 VectorStore Starter 会在启动期直接调用 `dimensions()`
	 * - 如果这里返回 null，系统会在 Bean 装配阶段直接失败
	 * - Dummy 模型的目标不是提供真实能力，而是让系统先启动，再提示用户补配置
	 */
	public EmbeddingModel getEmbeddingModel() {
		// 缓存命中时不加锁，降低向量检索高频调用的同步成本。
		if (currentEmbeddingModel == null) {
			// 只有首次初始化或 refresh 后才进入同步区。
			synchronized (this) {
				// 双重检查避免等待锁的线程重复创建模型。
				if (currentEmbeddingModel == null) {
					log.info("Initializing global EmbeddingModel...");
					try {
						// 查询当前启用的向量模型配置。
						ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
						if (config != null) {
							// 根据配置创建 OpenAI 协议兼容的 EmbeddingModel。
							currentEmbeddingModel = modelFactory.createEmbeddingModel(config);
						}
					}
					catch (Exception e) {
						// Embedding 初始化失败允许系统继续启动，因此这里只记录错误。
						log.error("Failed to initialize EmbeddingModel: {}", e.getMessage());
					}

					// 没有可用模型时放入占位实现，避免依赖方在 Bean 初始化阶段空指针。
					if (currentEmbeddingModel == null) {
						log.warn("Using DummyEmbeddingModel for fallback.");
						currentEmbeddingModel = new DummyEmbeddingModel();
					}
				}
			}
		}
		// 无论真实模型还是 Dummy，都保证调用方得到非 null 的接口实现。
		return currentEmbeddingModel;
	}

	/**
	 * 清空 Chat 缓存。
	 *
	 * 这不会立即创建新模型，只是让下一次访问时按最新配置重新初始化。
	 */
	public void refreshChat() {
		// volatile 写入会立即对其他线程可见；下一次 getChatClient 会触发懒加载。
		this.currentChatClient = null;
		log.info("Chat cache cleared.");
	}

	/**
	 * 清空 Embedding 缓存；下一次访问时按数据库中的最新配置重新创建。
	 */
	public void refreshEmbedding() {
		// 这里只失效缓存，不在配置保存线程中同步创建耗时的模型客户端。
		this.currentEmbeddingModel = null;
		log.info("Embedding cache cleared.");
	}

	/**
	 * 启动兜底用的 Dummy Embedding 模型。
	 *
	 * 这个内部类的目标不是提供真实 embedding 能力，而是：
	 * 1. 让依赖 `EmbeddingModel` 的 Starter 能先完成启动
	 * 2. 在真正发生 embedding 调用时，再以明确错误暴露“尚未配置有效模型”
	 */
	private static class DummyEmbeddingModel implements EmbeddingModel {

		/**
		 * 拒绝真正的批量 Embedding 请求，明确提示用户先配置模型。
		 */
		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			// Dummy 只能解决启动期装配问题，不能伪造可用于检索的向量。
			throw new RuntimeException("No active EMBEDDING model. Please configure it first!");
		}

		/**
		 * 对单个 Document 的占位实现。
		 *
		 * 返回空向量只是为了满足接口契约，不能用于真实语义检索。
		 */
		@Override
		public float[] embed(Document document) {
			// 接口默认方法可能在启动探测中调用，这里返回长度为 0 的占位向量。
			return new float[0];
		}

		/**
		 * 对字符串输入返回空占位向量，不代表真实语义结果。
		 */
		@Override
		public float[] embed(String text) {
			// 不根据文本生成伪数据，避免误导向量检索结果。
			return new float[0];
		}

		/**
		 * 对批量字符串输入返回空列表，不生成任何假向量。
		 */
		@Override
		public List<float[]> embed(List<String> texts) {
			// List.of 返回不可变空列表，清楚表达“没有 Embedding 结果”。
			return List.of();
		}

		/**
		 * Dummy 无法构造包含 token 元数据的 EmbeddingResponse。
		 */
		@Override
		public EmbeddingResponse embedForResponse(List<String> texts) {
			// 保持占位实现的既有契约；真实业务请求应通过 call 方法得到明确异常。
			return null;
		}

		/**
		 * 返回一个兼容启动期校验的默认向量维度。
		 *
		 * 这里选择 1536 是因为它是常见 embedding 模型的维度之一，
		 * 主要目的是尽量降低 Starter 初始化阶段的兼容性问题。
		 */
		@Override
		public int dimensions() {
			// VectorStore 初始化时主要依赖这个值创建或校验向量列。
			return 1536;
		}

	}

}
