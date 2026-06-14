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

import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.dto.planner.Plan;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_FEEDBACK_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_REVIEW_ENABLED;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.IS_ONLY_NL2SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_NEXT_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_REPAIR_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_VALIDATION_ERROR;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_VALIDATION_STATUS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_GENERATE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REPORT_GENERATOR_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_NODE;

/**
 * 计划执行节点。
 *
 * 这个节点本身不执行 SQL、Python 或报告生成，它只负责解释 Planner 产出的执行计划，并决定当前这一步应该跳到哪个执行节点。
 *
 * 可以把它理解为“计划运行时的总调度入口”：
 * - 先验证 Planner 生成的 JSON 计划是否合规。
 * - 再根据当前步数拿到要执行的 `ExecutionStep`。
 * - 最后把下一跳节点名写回状态，交给 Dispatcher 或 Graph 继续调度。
 *
 * 为什么要有这一层：
 * - Planner 负责“想怎么做”。
 * - PlanExecutorNode 负责“按什么顺序真正落地执行”。
 * - 这样规划和执行可以解耦，后续插入人工审核、失败修复也更容易。
 */
@Slf4j
@Component
public class PlanExecutorNode implements NodeAction {

	/**
	 * 当前执行器允许直接跳转到的业务节点。
	 *
	 * 注意这是“可执行节点白名单”，用于保护系统不因为 Planner 输出异常节点名而跳到未知流程。
	 */
	private static final Set<String> SUPPORTED_NODES = Set.of(SQL_GENERATE_NODE, PYTHON_GENERATE_NODE,
			REPORT_GENERATOR_NODE);

	/**
	 * 执行计划校验与下一跳选择。
	 *
	 * 返回的状态主要有三类：
	 * - `PLAN_VALIDATION_STATUS`：计划是否通过校验。
	 * - `PLAN_NEXT_NODE`：通过校验后应该执行的下一个节点名。
	 * - `PLAN_CURRENT_STEP`：当计划执行完或推进后，需要更新的当前步号。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 当前实现是在“计划执行前”做校验，因此每次进入执行器都会重新检查一次计划。
		// 这不是最省成本的方案，但可以保证执行时拿到的是一份结构完整的计划。
		Plan plan;
		try {
			// 从 PLANNER_NODE_OUTPUT 解析结构化 Plan。
			plan = PlanProcessUtil.getPlan(state);
		}
		catch (Exception e) {
			log.error("Plan validation failed due to a parsing error.", e);
			return buildValidationResult(state, false,
					"Validation failed: The plan is not a valid JSON structure. Error: " + e.getMessage());
		}

		// 第一层校验：Plan 和 executionPlan 必须存在且非空。
		if (!validateExecutionPlanStructure(plan)) {
			return buildValidationResult(state, false,
					"Validation failed: The generated plan is empty or has no execution steps.");
		}

		// 第二层校验：逐个检查工具名称和工具专属参数。
		for (ExecutionStep step : plan.getExecutionPlan()) {
			String validationResult = validateExecutionStep(step);
			if (validationResult != null) {
				return buildValidationResult(state, false, validationResult);
			}
		}

		log.info("Plan validation successful.");

		// 如果开启人工复核，则在真正执行计划前先跳到人工反馈节点暂停。
		Boolean humanReviewEnabled = state.value(HUMAN_REVIEW_ENABLED, false);
		if (Boolean.TRUE.equals(humanReviewEnabled)) {
			log.info("Human review enabled: routing to human_feedback node");
			return Map.of(PLAN_VALIDATION_STATUS, true, PLAN_NEXT_NODE, HUMAN_FEEDBACK_NODE);
		}

		// 当前步号由 SQL/Python 节点成功后推进。
		int currentStep = PlanProcessUtil.getCurrentStepNumber(state);
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();

		// 纯 NL2SQL 模式完成计划后直接 END，不再生成报告。
		boolean isOnlyNl2Sql = state.value(IS_ONLY_NL2SQL, false);

		// 当前步号大于计划总步数，说明计划已经跑完。
		if (currentStep > executionPlan.size()) {
			log.info("Plan completed, current step: {}, total steps: {}", currentStep, executionPlan.size());
			return Map.of(PLAN_CURRENT_STEP, 1, PLAN_NEXT_NODE, isOnlyNl2Sql ? StateGraph.END : REPORT_GENERATOR_NODE,
					PLAN_VALIDATION_STATUS, true);
		}

		// 业务步号从 1 开始，因此读取 List 时减 1。
		ExecutionStep executionStep = executionPlan.get(currentStep - 1);

		// Planner 用 toolToUse 声明本步骤应进入哪个节点。
		String toolToUse = executionStep.getToolToUse();
		return determineNextNode(toolToUse);
	}

	/**
	 * 根据当前步骤声明的工具类型选择下一跳节点。
	 *
	 * `toolToUse` 本质上就是 Planner 产出的“执行节点名”，这里会在白名单内做一次检查后再放行。
	 */
	private Map<String, Object> determineNextNode(String toolToUse) {
		// 普通执行节点必须位于后端白名单中，不能直接信任模型生成的任意字符串。
		if (SUPPORTED_NODES.contains(toolToUse)) {
			log.info("Determined next execution node: {}", toolToUse);
			return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
		}
		else if (HUMAN_FEEDBACK_NODE.equals(toolToUse)) {
			// 人工反馈节点不是常规工具，但属于允许的控制节点。
			log.info("Determined next execution node: {}", toolToUse);
			return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
		}
		else {
			// 非法节点名写入校验错误，Dispatcher 会将流程退回 Planner 修复。
			return Map.of(PLAN_VALIDATION_STATUS, false, PLAN_VALIDATION_ERROR, "Unsupported node type: " + toolToUse);
		}
	}

	/**
	 * 校验计划对象及步骤列表是否存在。
	 */
	private boolean validateExecutionPlanStructure(Plan plan) {
		// 三个条件缺一不可，否则无法按步执行。
		return plan != null && plan.getExecutionPlan() != null && !plan.getExecutionPlan().isEmpty();
	}

	/**
	 * 验证单个执行步骤是否合法。
	 *
	 * 校验内容包括：
	 * - 工具类型是否在允许范围内。
	 * - 工具参数是否存在。
	 * - 不同节点要求的关键字段是否齐全。
	 *
	 * 返回值约定：
	 * - `null` 表示通过校验。
	 * - 非空字符串表示失败原因。
	 */
	private String validateExecutionStep(ExecutionStep step) {
		// toolToUse 为空或不在白名单时拒绝计划。
		if (step.getToolToUse() == null || !SUPPORTED_NODES.contains(step.getToolToUse())) {
			return "Validation failed: Plan contains an invalid tool name: '" + step.getToolToUse() + "' in step "
					+ step.getStep();
		}

		// 每种工具都依赖参数对象，缺失时无法执行。
		if (step.getToolParameters() == null) {
			return "Validation failed: Tool parameters are missing for step " + step.getStep();
		}

		// 再按工具类型校验专属必填字段。
		switch (step.getToolToUse()) {
			case SQL_GENERATE_NODE:
				if (!StringUtils.hasText(step.getToolParameters().getInstruction())) {
					return "Validation failed: SQL generation node is missing description in step " + step.getStep();
				}
				break;

			case PYTHON_GENERATE_NODE:
				if (!StringUtils.hasText(step.getToolParameters().getInstruction())) {
					return "Validation failed: Python generation node is missing instruction in step " + step.getStep();
				}
				break;

			case REPORT_GENERATOR_NODE:
				if (!StringUtils.hasText(step.getToolParameters().getSummaryAndRecommendations())) {
					return "Validation failed: Report generation node is missing summary_and_recommendations in step "
							+ step.getStep();
				}
				break;

			default:
				break;
		}

		// null 是该方法约定的“校验通过”结果。
		return null;
	}

	/**
	 * 统一构造计划校验结果。
	 *
	 * 当校验失败时，这里顺便累加 `PLAN_REPAIR_COUNT`，
	 * 方便下游 Dispatcher 判断是否需要回到 Planner 进行修复。
	 */
	private Map<String, Object> buildValidationResult(OverAllState state, boolean isValid, String errorMessage) {
		// 成功时只写状态，下一节点会在主 apply 流程中决定。
		if (isValid) {
			return Map.of(PLAN_VALIDATION_STATUS, true);
		}
		else {
			// 失败时保留具体原因，并推进修复次数。
			int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);
			return Map.of(PLAN_VALIDATION_STATUS, false, PLAN_VALIDATION_ERROR, errorMessage, PLAN_REPAIR_COUNT,
					repairCount + 1);
		}
	}

}
