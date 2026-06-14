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
import com.alibaba.cloud.ai.dataagent.mapper.AgentDatasourceMapper;
import com.alibaba.cloud.ai.dataagent.service.schema.SchemaService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SCHEMA_RECALL_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT;

/**
 * Schema 召回节点。
 *
 * 这个节点负责把“增强后的自然语言问题”映射成一批候选表和字段文档，供后续表关系推理与 SQL 生成使用。
 *
 * 主链路位置：
 * 1. 上游是 `QueryEnhanceNode`，此时问题已经被规范化为更适合检索的表达。
 * 2. 下游是 `TableRelationNode`，它会基于这里召回出的文档构建结构化 Schema。
 *
 * 设计原因：
 * - 先做表级召回，再按表名拉取字段级文档，可以明显减少噪声。
 * - 如果直接做全库字段检索，容易把无关字段带入后续 Prompt，导致模型理解偏移。
 *
 * 关键依赖：
 * - `SchemaService`：封装向量检索或索引检索逻辑。
 * - `AgentDatasourceMapper`：查询当前 Agent 的激活数据源。
 */
@Slf4j
@Component
@AllArgsConstructor
public class SchemaRecallNode implements NodeAction {

	private final SchemaService schemaService;

	private final AgentDatasourceMapper agentDatasourceMapper;

	/**
	 * 执行 Schema 召回。
	 *
	 * 返回给 Graph 的对象仍然是 generator，而不是表文档列表本身。
	 * generator 结束时会把真正的业务结果写入 state：
	 * - `TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT`
	 * - `COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT`
	 *
	 * 这样前端可以实时看到召回过程，而后续节点又能通过状态拿到结构化结果。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 问题增强节点已经把原始问题转换成结构化 DTO。
		QueryEnhanceOutputDTO queryEnhanceOutputDTO = StateUtil.getObjectValue(state, QUERY_ENHANCE_NODE_OUTPUT,
				QueryEnhanceOutputDTO.class);
		// 使用规范化问题执行向量召回，比直接使用口语化原始输入更稳定。
		String input = queryEnhanceOutputDTO.getCanonicalQuery();
		// agentId 用于确定该智能体绑定的是哪一个数据源。
		String agentId = StateUtil.getStringValue(state, AGENT_ID);

		// 每个智能体可能绑定多个数据源，但运行态通常只有一个“当前激活数据源”参与本次分析。
		Integer datasourceId = agentDatasourceMapper.selectActiveDatasourceIdByAgentId(Long.valueOf(agentId));

		// 无活动数据源时无法做 Schema 召回，进入可展示的失败分支。
		if (datasourceId == null) {
			log.warn("Agent {} has no active datasource", agentId);
			String noDataSourceMessage = """
					\n 该智能体没有激活的数据源。

					这可能是因为：
					1. 数据源尚未配置或未关联到当前智能体。
					2. 所有关联数据源都被禁用。
					3. 当前尚未设置激活数据源。
					流程已终止。
					""";

			// 手工创建展示流，把原因发送给前端后正常完成，避免连接悬挂。
			Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
				emitter.next(ChatResponseUtil.createResponse(noDataSourceMessage));
				emitter.complete();
			});

			// 即使失败，也返回一个合法 generator，让前端和 Graph 走统一处理逻辑。
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil
				.createStreamingGeneratorWithMessages(this.getClass(), state, currentState -> Map.of(
						TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, Collections.emptyList(),
						COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, Collections.emptyList()), displayFlux);

			return Map.of(SCHEMA_RECALL_NODE_OUTPUT, generator);
		}

		// 先召回表，再根据命中的表名去拉字段文档，减少字段级检索噪声。
		List<Document> tableDocuments = new ArrayList<>(
				schemaService.getTableDocumentsByDatasource(datasourceId, input));
		// 从召回到的表文档 metadata 中提取表名，作为字段检索的过滤条件。
		List<String> recalledTableNames = extractTableName(tableDocuments);
		// 只查询命中表的字段，避免把整个数据库的列都塞进后续 Prompt。
		List<Document> columnDocuments = schemaService.getColumnDocumentsByTableName(datasourceId, recalledTableNames);

		String failMessage = """
				\n 未检索到相关数据表。

				这可能是因为：
				1. 数据源尚未完成初始化。
				2. 当前问题与数据库中的业务表结构关联较弱。
				3. 可以尝试重新初始化数据源，或换一个更贴近业务的问题。
				4. 如果初始化索引时使用的嵌入模型已经更换，通常需要重新初始化数据源。
				流程已终止。
				""";

		// 这里没有真正的 LLM token 流，因此通过 `Flux.create` 手工发出过程提示。
		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			// 第一个事件提示召回阶段已经开始。
			emitter.next(ChatResponseUtil.createResponse("开始初步召回 Schema 信息..."));
			// 第二个事件报告命中数量和表名，便于观察召回质量。
			emitter.next(ChatResponseUtil.createResponse(
					"初步表召回完成，数量: " + tableDocuments.size() + "，表名: " + String.join(", ", recalledTableNames)));
			// 没有命中表时追加具体排查建议。
			if (tableDocuments.isEmpty()) {
				emitter.next(ChatResponseUtil.createResponse(failMessage));
			}
			// 最后发送阶段完成事件并关闭 Flux。
			emitter.next(ChatResponseUtil.createResponse("初步 Schema 召回完成。"));
			emitter.complete();
		});

		// 展示流结束时，把表文档和字段文档同时写入 Graph state。
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, currentState -> Map.of(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, tableDocuments,
						COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, columnDocuments), displayFlux);

		// 当前节点的直接返回值仍是可订阅的生成器。
		return Map.of(SCHEMA_RECALL_NODE_OUTPUT, generator);
	}

	/**
	 * 从表文档中提取表名。
	 *
	 * `Document` 是 Spring AI 的通用文档对象，正文通常放在 content 中，扩展属性放在 metadata 中。
	 * 这里约定表名保存在 metadata 的 `name` 字段，因此后续所有 schema 相关服务都依赖这个约定。
	 */
	private static List<String> extractTableName(List<Document> tableDocuments) {
		// 使用新列表收集合法表名，不修改传入文档集合。
		List<String> tableNames = new ArrayList<>();
		// 逐个读取每份表文档的 metadata。
		for (Document document : tableDocuments) {
			// SchemaService 在建立索引时把真实表名写入 name 字段。
			String name = (String) document.getMetadata().get("name");
			// 忽略缺失或空表名，避免后续查询产生无效过滤条件。
			if (name != null && !name.isEmpty()) {
				tableNames.add(name);
			}
		}
		log.info("At this SchemaRecallNode, recall tables are: {}", tableNames);
		return tableNames;
	}

}
