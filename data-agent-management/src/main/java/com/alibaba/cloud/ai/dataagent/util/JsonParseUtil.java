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
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.prompt.PromptConstant;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * JSON 解析工具，支持在模型输出格式不合法时调用 LLM 自动修复。
 *
 * 大模型可能返回 Markdown 代码块、思考内容或少量语法错误，本类把清洗、解析和有限重试集中处理。
 */
@Slf4j
@Component
@AllArgsConstructor
public class JsonParseUtil {

	// JSON 修复失败时通过 LLM 重新整理文本。
	private LlmService llmService;

	// 限制修复次数，避免模型持续返回错误内容时形成无限循环。
	private static final int MAX_RETRY_COUNT = 3;

	// 部分推理模型会在最终答案前输出 think 区域，只保留最后一个结束标签之后的内容。
	private static final String THINK_END_TAG = "</think>";

	/**
	 * 把 JSON 转换为普通 Class 表示的对象，例如 QueryEnhanceOutputDTO。
	 */
	public <T> T tryConvertToObject(String json, Class<T> clazz) {
		// 在真正解析前尽早检查调用参数，让错误位置更接近调用方。
		Assert.hasText(json, "Input JSON string cannot be null or empty");
		Assert.notNull(clazz, "Target class cannot be null");

		// 把具体的 readValue 方式交给统一重试流程执行。
		return tryConvertToObjectInternal(json, (mapper, currentJson) -> mapper.readValue(currentJson, clazz));
	}

	/**
	 * 把 JSON 转换为 TypeReference 表示的泛型对象，例如 List&lt;String&gt;。
	 */
	public <T> T tryConvertToObject(String json, TypeReference<T> typeReference) {
		// TypeReference 不能为 null，否则 Jackson 无法得知泛型结构。
		Assert.hasText(json, "Input JSON string cannot be null or empty");
		Assert.notNull(typeReference, "TypeReference cannot be null");

		// 泛型版本与普通 Class 版本共用同一套清洗和修复策略。
		return tryConvertToObjectInternal(json, (mapper, currentJson) -> mapper.readValue(currentJson, typeReference));
	}

	/**
	 * 执行“直接解析一次，失败后最多修复三次”的统一流程。
	 */
	private <T> T tryConvertToObjectInternal(String json, JsonParserFunction<T> parser) {
		// 保留原始输出日志，方便定位是哪一个模型响应造成了解析失败。
		log.info("Trying to convert JSON to object: {}", json);
		// 先去掉推理模型的思考内容，避免它干扰 JSON 解析。
		String currentJson = removeThinkTags(json);
		// 保存最后一次异常，在所有重试失败后作为根因抛出。
		Exception lastException = null;
		// 使用项目统一 ObjectMapper，保证字段命名和序列化配置一致。
		ObjectMapper objectMapper = JsonUtil.getObjectMapper();

		try {
			// 大多数正常响应会在这里直接成功，不需要调用额外模型。
			return parser.parse(objectMapper, currentJson);
		}
		catch (JsonProcessingException e) {
			// 初次异常只触发修复流程，不立即中断整个工作流。
			log.warn("Initial parsing failed, preparing to call LLM: {}", e.getMessage());
		}

		// 每一轮都让 LLM 根据当前 JSON 和上一轮错误给出一个修复版本。
		for (int i = 0; i < MAX_RETRY_COUNT; i++) {
			try {
				// 第一轮没有保存初始异常，因此使用 Unknown error；后续轮次会带上具体 Jackson 错误。
				currentJson = callLlmToFix(currentJson,
						lastException != null ? lastException.getMessage() : "Unknown error");

				// 修复后立即重新解析；成功便结束循环并返回对象。
				return parser.parse(objectMapper, currentJson);
			}
			catch (JsonProcessingException e) {
				// 记录本轮失败原因，供下一轮修复提示和最终异常使用。
				lastException = e;
				log.warn("Still failed after {} fix attempt: {}", i + 1, e.getMessage());

				// 最后一轮仍失败时记录最终文本，便于开发者复现问题。
				if (i == MAX_RETRY_COUNT - 1) {
					log.error("Finally failed after {} fix attempts", MAX_RETRY_COUNT);
					log.warn("Last fix result: {}", currentJson);
				}
			}
		}

		// 所有修复机会耗尽后，将解析失败交给上层节点决定降级或终止。
		throw new IllegalArgumentException(
				String.format("Failed to parse JSON after %d LLM fix attempts", MAX_RETRY_COUNT), lastException);
	}

	/**
	 * 抽象 Jackson 的具体 readValue 调用，让 Class 和 TypeReference 两种入口复用重试代码。
	 */
	@FunctionalInterface
	private interface JsonParserFunction<T> {

		// 实现只负责把当前 JSON 转成目标类型，重试次数由外层方法管理。
		T parse(ObjectMapper mapper, String json) throws JsonProcessingException;

	}

	/**
	 * 请求 LLM 修复当前 JSON；调用异常时返回原文，让外层继续按统一规则处理。
	 */
	private String callLlmToFix(String json, String errorMessage) {
		try {
			// Prompt 同时提供错误 JSON 和 Jackson 错误信息，帮助模型进行定向修复。
			String prompt = PromptConstant.getJsonFixPromptTemplate()
				.render(Map.of("json_string", json, "error_message", errorMessage));

			// 修复接口仍然是流式响应，需要把所有 token 拼成一段完整 JSON。
			Flux<ChatResponse> responseFlux = llmService.callUser(prompt);
			String fixedJson = llmService.toStringFlux(responseFlux)
				.collect(StringBuilder::new, StringBuilder::append)
				.map(StringBuilder::toString)
				.block();

			// block 可能因空流得到 null，此时保留原文，不把 null 传给 Jackson。
			if (fixedJson == null) {
				log.warn("LLM fix returned null, using original JSON");
				return json;
			}

			// 先记录模型原始输出，再分步骤清洗，方便判断问题出现在哪一层。
			log.debug("LLM original return content: {}", fixedJson);

			// 移除推理过程，只保留模型给出的最终答案。
			String cleanedJson = removeThinkTags(fixedJson);
			log.debug("Content after removing think tags: {}", cleanedJson);

			// 模型常把 JSON 包在 ```json 代码块中，这里提取代码块里的原始文本。
			cleanedJson = MarkdownParserUtil.extractRawText(cleanedJson);
			log.debug("Content after extracting Markdown code blocks: {}", cleanedJson);

			// Markdown 清洗若意外返回 null，则仍回退到修复前文本。
			return cleanedJson != null ? cleanedJson : json;
		}
		catch (Exception e) {
			// 网络、模型或 Prompt 渲染异常不在这里终止流程，由外层解析重试统一收口。
			log.error("Exception occurred while calling LLM fix service", e);
			return json;
		}
	}

	/**
	 * 找到最后一个 {@code </think>}，只保留标签之后的最终回答。
	 */
	private String removeThinkTags(String text) {
		// 空输入无需处理，也避免后续 lastIndexOf 调用出现空指针。
		if (text == null || text.isEmpty()) {
			return text;
		}

		// 使用最后一个结束标签，兼容响应中出现多个思考片段的情况。
		int lastEndTagIndex = text.lastIndexOf(THINK_END_TAG);

		// 找到标签后，截掉标签本身以及它之前的全部推理文本。
		if (lastEndTagIndex != -1) {
			log.debug("Found </think> tag, index position: {}", lastEndTagIndex);

			// 起点需要加上标签长度，否则结果中仍会包含 </think>。
			int contentStartIndex = lastEndTagIndex + THINK_END_TAG.length();

			// trim 同时清除标签后常见的换行和首尾空格。
			String finalResult = text.substring(contentStartIndex).trim();

			log.debug("Content after truncating think tags: {}", finalResult);

			// 返回真正参与 JSON 解析的最终回答。
			return finalResult;
		}

		// 没有 think 标签通常表示普通模型响应，直接返回去除首尾空格后的原文。
		log.debug("Think end tag not found, returning original text");
		return text.trim();
	}

}
