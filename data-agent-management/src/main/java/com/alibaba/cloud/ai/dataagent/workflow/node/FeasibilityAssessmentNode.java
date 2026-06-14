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

import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
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
import static com.alibaba.cloud.ai.dataagent.constant.Constant.FEASIBILITY_ASSESSMENT_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;

/**
 * 可行性评估节点。
 *
 * 它位于表关系推理之后、规划器之前，目的是进一步判断当前问题是否真的适合继续进入数据分析主链路。
 *
 * 与最前面的“意图识别”相比，这里拥有更多上下文：
 * - 增强后的用户问题
 * - 已经整理过的 Schema
 * - 证据召回结果
 * - 多轮对话上下文
 *
 * 因此它能做更细粒度的分析，例如：
 * - 这个问题是否具备明确的数据分析目标。
 * - 当前 Schema 是否足以支撑分析。
 * - 用户真正想要的是数据结论，还是普通闲聊/咨询。
 */
@Slf4j
@Component
@AllArgsConstructor
public class FeasibilityAssessmentNode implements NodeAction {

	private final LlmService llmService;

	/**
	 * 执行可行性评估。
	 *
	 * 结果会以文本形式写入 `FEASIBILITY_ASSESSMENT_NODE_OUTPUT`。
	 * 下游 `FeasibilityAssessmentDispatcher` 会继续解析这段文本中的结构化约定字段，并决定是否进入规划阶段。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 规范化问题代表用户最终想分析的业务目标。
		String canonicalQuery = StateUtil.getCanonicalQuery(state);
		// 召回并整理后的 Schema 用于判断现有数据是否足以回答问题。
		SchemaDTO recalledSchema = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		// 业务证据补充数据库结构本身无法表达的指标口径。
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		// 历史对话可帮助识别“它”“上个月”等依赖上下文的表达。
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(无)");

		// Prompt 同时注入问题、Schema、证据和多轮上下文，避免模型只凭一句话做空判断。
		String prompt = PromptHelper.buildFeasibilityAssessmentPrompt(canonicalQuery, recalledSchema, evidence,
				multiTurn);
		log.debug("Built feasibility assessment prompt as follows \n {} \n", prompt);

		// 请求模型输出带固定约定字段的可行性判断文本。
		Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

		// 包装成 Graph 流，在前端显示阶段提示，并在完成时保存完整判断结果。
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "正在进行可行性评估...", "可行性评估完成。", llmOutput -> {
					// 去除模型输出首尾空白，避免 Dispatcher 的文本匹配受到换行影响。
					String assessmentResult = llmOutput.trim();
					log.info("Feasibility assessment result: {}", assessmentResult);
					return Map.of(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, assessmentResult);
				}, responseFlux);
		// Dispatcher 会读取该 generator 最终写入的文本并选择下一节点。
		return Map.of(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, generator);
	}

}
