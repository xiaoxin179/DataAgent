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

import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SCHEMA_RECALL_NODE;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 问题增强分发器。
 *
 * 这个 Dispatcher 检查 `QueryEnhanceNode` 生成的结构化问题是否完整。
 * 只有当增强结果至少包含：
 * - `canonicalQuery`
 * - `expandedQueries`
 *
 * 工作流才有足够稳定的输入继续进入 Schema 召回阶段。
 */
@Slf4j
public class QueryEnhanceDispatcher implements EdgeAction {

	/**
	 * 校验查询增强 DTO 是否包含后续 Schema 召回所需的字段。
	 */
	@Override
	public String apply(OverAllState state) throws Exception {
		// 将 state 中的 JSON/Map 形式结果恢复为类型明确的 DTO。
		QueryEnhanceOutputDTO queryProcessOutput = StateUtil.getObjectValue(state, QUERY_ENHANCE_NODE_OUTPUT,
				QueryEnhanceOutputDTO.class);

		// 模型输出无法解析时没有可靠的检索词，结束当前图。
		if (queryProcessOutput == null) {
			log.warn("Query process output is null, ending conversation");
			return END;
		}

		// canonicalQuery 是消除多轮指代后的标准问题。
		boolean isCanonicalQueryEmpty = queryProcessOutput.getCanonicalQuery() == null
				|| queryProcessOutput.getCanonicalQuery().trim().isEmpty();

		// expandedQueries 用于扩大 Schema 向量召回覆盖面。
		boolean isExpandedQueriesEmpty = queryProcessOutput.getExpandedQueries() == null
				|| queryProcessOutput.getExpandedQueries().isEmpty();

		// 任一核心字段为空都不能稳定执行下游检索。
		if (isCanonicalQueryEmpty || isExpandedQueriesEmpty) {
			log.warn("Query process output contains empty fields - canonicalQuery: {}, expandedQueries: {}",
					isCanonicalQueryEmpty, isExpandedQueriesEmpty);
			return END;
		}
		else {
			// DTO 完整时进入 Schema 召回阶段。
			log.info("Query process output is valid, proceeding to schema recall");
			return SCHEMA_RECALL_NODE;
		}
	}

}
