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
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_NODE;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * Schema 召回分发器。
 *
 * 当前节点的判断原则非常直接：
 * - 召回到表文档，说明后续还有继续构建 Schema 的基础，进入 `TABLE_RELATION_NODE`。
 * - 一个表都没召回到，后面继续让模型猜只会制造噪声，因此直接结束。
 */
@Slf4j
public class SchemaRecallDispatcher implements EdgeAction {

	/**
	 * 根据表级召回结果判断是否有必要继续构建表关系。
	 */
	@Override
	public String apply(OverAllState state) throws Exception {
		// SchemaRecallNode 把候选表以 Document 列表写入 state。
		List<Document> tableDocuments = StateUtil.getDocumentList(state, TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT);

		// 至少召回一个候选表时，交给 TableRelationNode 恢复结构和关系。
		if (tableDocuments != null && !tableDocuments.isEmpty()) {
			return TABLE_RELATION_NODE;
		}

		// 没有候选表意味着无法可靠生成 SQL，直接结束而不是让模型猜测。
		log.info("No table documents found, ending conversation");
		return END;
	}

}
