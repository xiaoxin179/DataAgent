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
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.MarkdownParserUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;

/**
 * 问题增强节点。
 *
 * 这个节点的目标不是直接生成 SQL，而是先把用户原始问句加工成更适合后续检索和规划使用的版本。
 * 典型增强内容包括：
 * - 结合 evidence 补全隐含业务语义
 * - 结合多轮上下文消歧
 * - 输出更结构化、更明确的后续查询描述
 *
 * 在整个主链路中，它位于证据召回之后、Schema 召回之前，
 * 是“用户自然语言问题”向“系统可执行问题描述”过渡的一环。
 */
@Slf4j
@Component
@AllArgsConstructor
public class QueryEnhanceNode implements NodeAction {

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	/**
	 * 执行问题增强。
	 *
	 * 返回给 Graph 的不是最终 DTO，而是一条流式生成器：
	 * - 前端能实时看到“正在进行问题增强”
	 * - 流结束后再统一把 JSON 结果解析成 `QueryEnhanceOutputDTO`
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// INPUT_KEY 保存本轮用户最原始的自然语言问题。
		String userInput = StateUtil.getStringValue(state, INPUT_KEY);
		log.info("User input for query enhance: {}", userInput);

		// 业务知识证据帮助模型补全缩写、指标和口径。
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		// 多轮上下文可选；缺失时用“无”明确告诉模型没有历史信息。
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(无)");

		// PromptHelper 统一组织上下文，避免节点手工拼接导致模板不一致。
		String prompt = PromptHelper.buildQueryEnhancePrompt(multiTurn, userInput, evidence);
		log.debug("Built query enhance prompt as follows \n {} \n", prompt);

		// 模型响应是 Flux，每个 ChatResponse 只包含一部分 JSON 文本。
		Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

		// 前置事件打开 JSON 标记，后置事件关闭标记并提示完成。
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				responseFlux,
				Flux.just(ChatResponseUtil.createResponse("正在进行问题增强..."),
						ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
						ChatResponseUtil.createResponse("\n问题增强完成")),
				this::handleQueryEnhance);

		// Graph 订阅 generator 后，handleQueryEnhance 才会收到完整模型输出。
		return Map.of(QUERY_ENHANCE_NODE_OUTPUT, generator);
	}

	/**
	 * 处理模型返回的问题增强结果。
	 *
	 * 这里会先去掉 markdown 包裹，再尝试反序列化成 DTO。
	 * 如果解析失败，不直接抛异常中断流程，而是返回空 Map，让后续节点按缺省策略继续工作。
	 */
	private Map<String, Object> handleQueryEnhance(String llmOutput) {
		// 去掉 ```json 等 Markdown 外壳，只保留可解析的 JSON 正文。
		String enhanceResult = MarkdownParserUtil.extractRawText(llmOutput.trim());
		log.info("Query enhance result: {}", enhanceResult);

		// 先声明为 null，解析失败时可统一走空结果分支。
		QueryEnhanceOutputDTO queryEnhanceOutputDTO = null;
		try {
			// JsonParseUtil 会在格式错误时最多调用 LLM 修复三次。
			queryEnhanceOutputDTO = jsonParseUtil.tryConvertToObject(enhanceResult, QueryEnhanceOutputDTO.class);
			log.info("Successfully parsed query enhance result: {}", queryEnhanceOutputDTO);
		}
		catch (Exception e) {
			log.error("Failed to parse query enhance result: {}", enhanceResult, e);
		}

		// 无法得到合法 DTO 时不写入错误类型的数据。
		if (queryEnhanceOutputDTO == null) {
			return Map.of();
		}
		// 成功时把结构化 DTO 写入状态，后续节点可读取 canonicalQuery。
		return Map.of(QUERY_ENHANCE_NODE_OUTPUT, queryEnhanceOutputDTO);
	}

}
