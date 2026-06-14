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

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_EXECUTOR_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;

/**
 * SQL 执行分发器。
 *
 * SQL 执行节点跑完之后，流程并不会机械地往下走，而是要先看执行结果：
 * - 如果执行失败且重试 DTO 标记为需要重新生成 SQL，则回到 `SQL_GENERATE_NODE`。
 * - 否则说明这一步已经完成，回到 `PLAN_EXECUTOR_NODE` 让执行器决定下一步计划。
 */
@Slf4j
public class SQLExecutorDispatcher implements EdgeAction {

	/**
	 * 根据 SQL 执行阶段写入的重试原因决定重生成还是推进计划。
	 */
	@Override
	public String apply(OverAllState state) {
		// SqlExecuteNode 会把成功或失败来源封装到 SqlRetryDto 中。
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class);

		// 数据库执行失败时回到 SQL 生成节点，并让它读取错误原因修复旧 SQL。
		if (retryDto.sqlExecuteFail()) {
			log.warn("SQL 执行失败，需要重新生成。");
			return SQL_GENERATE_NODE;
		}
		else {
			// 没有执行失败标记表示当前计划步骤完成，回执行器选择下一步骤。
			log.info("SQL 执行成功，返回 PlanExecutorNode。");
			return PLAN_EXECUTOR_NODE;
		}
	}

}
