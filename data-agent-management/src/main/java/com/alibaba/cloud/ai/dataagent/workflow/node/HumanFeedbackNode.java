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

import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_FEEDBACK_DATA;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_REVIEW_ENABLED;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_EXECUTOR_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_REPAIR_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_VALIDATION_ERROR;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;

/**
 * 人工反馈节点。
 *
 * 这是整条 Graph 中最关键的“人工介入点”之一。
 * 当系统启用了人工复核后，流程会在这里暂停，等待前端或外部系统把人工审批结果写回状态。
 *
 * 该节点本身不做复杂业务，只做三件事：
 * 1. 判断是否已经拿到人工反馈。
 * 2. 反馈通过则恢复执行计划。
 * 3. 反馈拒绝则回到 Planner 重新生成计划。
 */
@Slf4j
@Component
public class HumanFeedbackNode implements NodeAction {

	/**
	 * 处理人工反馈结果。
	 *
	 * 关键状态：
	 * - `HUMAN_FEEDBACK_DATA`：前端或外部系统写入的审批结果。
	 * - `human_next_node`：当前节点最终告诉 Dispatcher 的下一跳。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 本节点可能写入多个路由和修复状态，因此使用可变 Map 逐项组装。
		Map<String, Object> updated = new HashMap<>();

		// 限制计划被人工否决后的重新生成次数，防止 Planner 无限循环。
		int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);
		// 达到上限后直接通知 Dispatcher 结束流程。
		if (repairCount >= 3) {
			log.warn("Max repair attempts (3) exceeded, ending process");
			updated.put("human_next_node", "END");
			return updated;
		}

		@SuppressWarnings("unchecked")
		// 首次到达该节点时通常没有反馈数据，因此默认使用空 Map。
		Map<String, Object> feedbackData = StateUtil.getObjectValue(state, HUMAN_FEEDBACK_DATA, Map.class, Map.of());
		if (feedbackData.isEmpty()) {
			// 还没拿到人工反馈时，不继续执行，而是告诉 Dispatcher 进入等待态。
			updated.put("human_next_node", "WAIT_FOR_FEEDBACK");
			return updated;
		}

		// feedback 缺省为 true，兼容旧客户端只提交反馈内容而不提交布尔值的情况。
		Object approvedValue = feedbackData.getOrDefault("feedback", true);
		// 同时兼容 JSON Boolean 和字符串 "true"/"false"。
		boolean approved = approvedValue instanceof Boolean approvedBoolean ? approvedBoolean
				: Boolean.parseBoolean(approvedValue.toString());

		// 审批通过后恢复执行计划。
		if (approved) {
			log.info("Plan approved -> execution");
			// Dispatcher 根据 human_next_node 跳转到计划执行节点。
			updated.put("human_next_node", PLAN_EXECUTOR_NODE);
			// 清除人工审核开关，避免后续步骤再次停在该节点。
			updated.put(HUMAN_REVIEW_ENABLED, false);
		}
		else {
			// 审批拒绝后回到 Planner，并把反馈作为修复依据。
			log.info("Plan rejected -> regeneration (attempt {})", repairCount + 1);
			updated.put("human_next_node", PLANNER_NODE);
			// 记录本次失败次数，下次进入节点时继续检查上限。
			updated.put(PLAN_REPAIR_COUNT, repairCount + 1);
			// 新计划需要从第一步重新开始执行。
			updated.put(PLAN_CURRENT_STEP, 1);
			// 新计划生成后仍需要再次人工审核。
			updated.put(HUMAN_REVIEW_ENABLED, true);

			// 提取用户填写的具体拒绝原因。
			String feedbackContent = feedbackData.getOrDefault("feedback_content", "").toString();
			// 没有填写原因时提供默认信息，保证 Planner 总能读到非空修复说明。
			updated.put(PLAN_VALIDATION_ERROR,
					StringUtils.hasLength(feedbackContent) ? feedbackContent : "Plan rejected by user");

			// 计划被否决时，清空旧计划输出，避免下一轮 Planner 修复误用老结果。
			updated.put(PLANNER_NODE_OUTPUT, "");
		}

		// Graph 会把 updated 中的每个键合并回共享状态。
		return updated;
	}

}
