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

import com.alibaba.cloud.ai.dataagent.dto.planner.Plan;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptConstant;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
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
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.IS_ONLY_NL2SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_VALIDATION_ERROR;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;

/**
 * 规划节点。
 *
 * 这个节点负责把“用户问题 + schema + 证据 + 语义模型”等上下文整理成一份结构化执行计划。
 * 它不直接执行 SQL，也不直接跑 Python，而是告诉后续节点“应该怎么执行”。
 *
 * 在工作流里的定位：
 * - 上游：QueryEnhance、SchemaRecall、TableRelation 等节点负责准备上下文。
 * - 当前：PlannerNode 负责生成 Plan。
 * - 下游：PlanExecutorNode 读取 Plan，决定下一跳是 SQL、Python 还是报告生成。
 *
 * 关键框架 API：
 * - {@link NodeAction}：Graph 框架中节点逻辑的统一接口。
 * - {@link OverAllState}：工作流共享状态容器。
 * - {@link BeanOutputConverter}：为大模型生成结构化输出格式说明，减少 JSON 解析失败概率。
 */
@Slf4j
@Component
@AllArgsConstructor
public class PlannerNode implements NodeAction {

	private final LlmService llmService;

	/**
	 * 执行规划节点并返回写回 Graph 状态的结果。
	 *
	 * 注意当前返回值不是 `Plan` 对象，而是一个包含 `PLANNER_NODE_OUTPUT` 的状态 Map。
	 * 其中 `PLANNER_NODE_OUTPUT` 对应的是一个流式生成器，Graph 会继续消费它并向上游发流。
	 *
	 * 这里显式拼接 JSON 起止标记，是为了让前后端都能识别“这一段输出是结构化 JSON”。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 精简模式使用内置单步计划，完整模式调用模型规划。
		Boolean onlyNl2sql = state.value(IS_ONLY_NL2SQL, false);

		// 根据模式选择计划内容来源。
		Flux<ChatResponse> flux = onlyNl2sql ? handleNl2SqlOnly() : handlePlanGenerate(state);

		// 给计划正文添加 JSON 类型边界，供 GraphService 和前端识别。
		Flux<ChatResponse> chatResponseFlux = Flux.concat(
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())), flux,
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign())));
		// 流结束时去掉边界标记，仅把纯 JSON 写入 PLANNER_NODE_OUTPUT。
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> Map.of(PLANNER_NODE_OUTPUT, v.substring(TextType.JSON.getStartSign().length(),
						v.length() - TextType.JSON.getEndSign().length())),
				chatResponseFlux);

		// 返回节点流，Graph 框架消费完成后再合并最终计划状态。
		return Map.of(PLANNER_NODE_OUTPUT, generator);
	}

	/**
	 * 正常模式下生成完整执行计划。
	 *
	 * 这一步是“检索上下文 -> 执行编排”之间的转换点。
	 * 规划质量直接决定后续节点是否会走错分支，因此是整条主链路中非常关键的一步。
	 */
	private Flux<ChatResponse> handlePlanGenerate(OverAllState state) {
		// 使用 QueryEnhanceNode 产出的标准问题，避免多轮指代影响计划。
		String canonicalQuery = StateUtil.getCanonicalQuery(state);
		log.info("Using processed query for planning: {}", canonicalQuery);

		// 非空表示上一版计划未通过校验或被用户拒绝，本轮属于修复规划。
		String validationError = StateUtil.getStringValue(state, PLAN_VALIDATION_ERROR, null);
		if (validationError != null) {
			log.info("Regenerating plan with user feedback: {}", validationError);
		}
		else {
			log.info("Generating initial plan");
		}

		// 读取表相关语义模型和结构化 Schema。
		String semanticModel = (String) state.value(GENEGRATED_SEMANTIC_MODEL_PROMPT).orElse("");
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);

		// 将 SchemaDTO 渲染成适合模型阅读的数据库说明。
		String schemaStr = PromptHelper.buildMixMacSqlDbPrompt(schemaDTO, true);

		// 修复场景会把旧计划和错误一起加入用户问题。
		String userPrompt = buildUserPrompt(canonicalQuery, validationError, state);

		// 业务证据帮助 Planner 理解指标和领域约束。
		String evidence = StateUtil.getStringValue(state, EVIDENCE);

		// `BeanOutputConverter` 会生成类似 JSON Schema 的格式说明，提示模型按 Plan 结构输出。
		BeanOutputConverter<Plan> beanOutputConverter = new BeanOutputConverter<>(Plan.class);
		Map<String, Object> params = Map.of("user_question", userPrompt, "schema", schemaStr, "evidence", evidence,
				"semantic_model", semanticModel, "plan_validation_error", formatValidationError(validationError),
				"format", beanOutputConverter.getFormat());
		// 将所有参数渲染进 Planner 模板。
		String plannerPrompt = PromptConstant.getPlannerPromptTemplate().render(params);
		log.debug("Planner prompt: as follows \n{}\n", plannerPrompt);

		// 返回模型 token 流，apply 负责添加类型标记和状态回写。
		return llmService.callUser(plannerPrompt);
	}

	/**
	 * 纯 NL2SQL 模式下直接返回一个内置简化计划。
	 *
	 * 这样做的目的是减少不必要的 LLM 规划步骤，降低延迟和不确定性。
	 */
	private Flux<ChatResponse> handleNl2SqlOnly() {
		return Flux.just(ChatResponseUtil.createPureResponse(Plan.nl2SqlPlan()));
	}

	/**
	 * 构造交给 Planner 的用户问题段。
	 *
	 * 如果存在 `validationError`，说明当前是在“修计划”而不是“第一次生成计划”，
	 * 因此这里会把上一版计划和用户反馈一起拼进 prompt。
	 */
	private String buildUserPrompt(String input, String validationError, OverAllState state) {
		// 初次规划不需要携带旧计划。
		if (validationError == null) {
			return input;
		}

		// 修复规划时保留上一版计划，帮助模型针对错误做局部调整。
		String previousPlan = StateUtil.getStringValue(state, PLANNER_NODE_OUTPUT, "");
		return String.format(
				"IMPORTANT: User rejected previous plan with feedback: \"%s\"\n\n" + "Original question: %s\n\n"
						+ "Previous rejected plan:\n%s\n\n"
						+ "CRITICAL: Generate new plan incorporating user feedback (\"%s\")",
				validationError, input, previousPlan, validationError);
	}

	/**
	 * 将可选校验错误格式化为 Planner prompt 中的高优先级说明。
	 */
	private String formatValidationError(String validationError) {
		// 没有错误时返回空字符串，保持模板参数完整。
		return validationError != null ? String
			.format("**USER FEEDBACK (CRITICAL)**: %s\n\n**Must incorporate this feedback.**", validationError) : "";
	}

}
