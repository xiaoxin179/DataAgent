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
package com.alibaba.cloud.ai.dataagent.workflow.dispatcher;

import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import java.util.Optional;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.FEASIBILITY_ASSESSMENT_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_EXCEPTION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_RETRY_COUNT;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 表关系推理分发器。
 *
 * 它负责消费 `TableRelationNode` 写回的状态，并基于以下信息做路由：
 * - 是否存在异常标记。
 * - 异常是否允许重试。
 * - 当前重试次数是否超过上限。
 * - 是否已经成功产出 `TABLE_RELATION_OUTPUT`。
 *
 * 这种“节点负责执行业务，Dispatcher 负责重试和跳转”的拆分，在 Graph 工作流里非常常见。
 */
public class TableRelationDispatcher implements EdgeAction {

	private static final int MAX_RETRY_COUNT = 3;

	/**
	 * 根据表关系构建结果、异常类型和次数决定成功、重试或终止。
	 */
	@Override
	public String apply(OverAllState state) throws Exception {
		// 节点使用异常字符串表达失败原因，可重试错误带固定前缀。
		String errorFlag = StateUtil.getStringValue(state, TABLE_RELATION_EXCEPTION_OUTPUT, null);

		// 没有计数时视为第一次尝试。
		Integer retryCount = StateUtil.getObjectValue(state, TABLE_RELATION_RETRY_COUNT, Integer.class, 0);

		// 先处理异常，因为旧的 TABLE_RELATION_OUTPUT 可能仍然残留在 state 中。
		if (errorFlag != null && !errorFlag.isEmpty()) {
			// 只有显式可重试并且未达到上限时才回到同一节点。
			if (isRetryableError(errorFlag) && retryCount < MAX_RETRY_COUNT) {
				return TABLE_RELATION_NODE;
			}
			else {
				// 不可重试错误或次数用尽时终止，防止循环。
				return END;
			}
		}

		// 没有异常后再检查是否已经成功生成结构化 Schema。
		Optional<String> tableRelationOutput = state.value(TABLE_RELATION_OUTPUT);
		if (tableRelationOutput.isPresent()) {
			return FEASIBILITY_ASSESSMENT_NODE;
		}

		// 没有异常，也没有结果，说明当前轮次无法继续，直接终止。
		return END;
	}

	/**
	 * 当前实现采用轻量文本协议表示“是否允许重试”。
	 *
	 * 只要异常字符串以 `RETRYABLE:` 开头，Dispatcher 就认为这是一个可重试失败。
	 * 这样做的优点是实现成本低，但缺点是协议要靠上下游共同遵守。
	 */
	private boolean isRetryableError(String errorMessage) {
		// 前缀由 TableRelationNode 写入，双方共同遵守这一轻量协议。
		return errorMessage.startsWith("RETRYABLE:");
	}

}
