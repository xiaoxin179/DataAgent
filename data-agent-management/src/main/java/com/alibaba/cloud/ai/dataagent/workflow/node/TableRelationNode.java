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

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import com.alibaba.cloud.ai.dataagent.entity.AgentDatasource;
import com.alibaba.cloud.ai.dataagent.entity.LogicalRelation;
import com.alibaba.cloud.ai.dataagent.entity.SemanticModel;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.service.datasource.AgentDatasourceService;
import com.alibaba.cloud.ai.dataagent.service.datasource.DatasourceService;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.service.schema.SchemaService;
import com.alibaba.cloud.ai.dataagent.service.semantic.SemanticModelService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.DB_DIALECT_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_SCHEMA_MISSING_ADVICE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_EXCEPTION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_RETRY_COUNT;
import static com.alibaba.cloud.ai.dataagent.prompt.PromptHelper.buildSemanticModelPrompt;

/**
 * 表关系推理节点。
 *
 * `SchemaRecallNode` 只能给出“可能相关的表和字段文档”，但这还不足以直接生成 SQL。
 * 当前节点负责把这些粗召回结果进一步整理为真正可用于 NL2SQL 的结构化 Schema。
 *
 * 主要职责：
 * - 基于表文档、字段文档构建初始 `SchemaDTO`。
 * - 合并系统维护的逻辑外键，弥补数据库物理外键缺失带来的 JOIN 信息不足。
 * - 调用 `Nl2SqlService.fineSelect(...)` 对候选 Schema 再做一次精筛，去掉噪声表。
 * - 生成与当前保留表集合对应的语义模型 Prompt，供 SQL 生成阶段使用。
 *
 * 学习建议：
 * - 想理解 NL2SQL 为什么不是“一次模型调用就结束”，这里是关键切入点。
 * - 这一步体现了系统把“检索、结构化、模型判断”分层处理的设计思路。
 */
@Slf4j
@Component
@AllArgsConstructor
public class TableRelationNode implements NodeAction {

	private final SchemaService schemaService;

	private final Nl2SqlService nl2SqlService;

	private final SemanticModelService semanticModelService;

	private final DatabaseUtil databaseUtil;

	private final DatasourceService datasourceService;

	private final AgentDatasourceService agentDatasourceService;

	/**
	 * 执行表关系推理。
	 *
	 * 输入依赖：
	 * - `TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT` 和 `COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT` 是 Schema 召回阶段输出的候选文档。
	 * - `EVIDENCE` 是业务证据补充信息。
	 * - `AGENT_ID` 用于反查当前运行所绑定的数据源和数据库配置。
	 *
	 * 输出依赖：
	 * - `TABLE_RELATION_OUTPUT` 保存最终 `SchemaDTO`。
	 * - `DB_DIALECT_TYPE` 会被下游 SQL 生成节点读取，用于生成正确的数据库方言语句。
	 * - 异常与重试计数字段由 Dispatcher 消费，用于控制失败后的回跳策略。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 标准问题用于让模型从候选 Schema 中做相关性精筛。
		String canonicalQuery = StateUtil.getCanonicalQuery(state);

		// 业务证据帮助识别字段与业务术语的对应关系。
		String evidence = StateUtil.getStringValue(state, EVIDENCE);

		// SchemaRecallNode 分别保存候选表文档和字段文档。
		List<Document> tableDocuments = StateUtil.getDocumentList(state, TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT);
		List<Document> columnDocuments = StateUtil.getDocumentList(state, COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT);

		// agentId 用于查询数据源、逻辑外键和语义模型。
		String agentIdStr = StateUtil.getStringValue(state, AGENT_ID);

		// 先解析出当前 Agent 对应的数据库配置，最关键的是拿到 SQL 方言。
		DbConfigBO agentDbConfig = databaseUtil.getAgentDbConfig(Long.valueOf(agentIdStr));

		// 逻辑外键往往来自系统维护的元数据，而不是数据库真实外键。
		// 对业务库来说，这类信息对生成 JOIN 语句尤其重要。
		List<String> logicalForeignKeys = getLogicalForeignKeys(Long.valueOf(agentIdStr), tableDocuments);
		log.info("Found {} logical foreign keys for agent: {}", logicalForeignKeys.size(), agentIdStr);

		// 把召回文档恢复成可供模型精筛的初始 SchemaDTO。
		SchemaDTO initialSchema = buildInitialSchema(agentIdStr, columnDocuments, tableDocuments, agentDbConfig,
				logicalForeignKeys);

		// resultMap 会在异步 schemaFlux 回调中继续写入最终 Schema 和语义模型。
		Map<String, Object> resultMap = new HashMap<>();

		// 方言和重试初始状态可以在模型流完成前同步写好。
		resultMap.put(DB_DIALECT_TYPE, agentDbConfig.getDialectType());
		resultMap.put(TABLE_RELATION_RETRY_COUNT, 0);
		resultMap.put(TABLE_RELATION_EXCEPTION_OUTPUT, "");

		// `fineSelect` 不是生成 SQL，而是让模型在初始 Schema 上继续做一次精筛，减少无关表对后续提示词的污染。
		Flux<ChatResponse> schemaFlux = processSchemaSelection(initialSchema, canonicalQuery, evidence, state,
				agentDbConfig, result -> {
					log.info("[{}] Schema processing result: {}", this.getClass().getSimpleName(), result);
					resultMap.put(TABLE_RELATION_OUTPUT, result);

					// 表筛选完成后，再反查这些表对应的语义模型，作为 SQL 生成阶段的补充上下文。
					// 只查询最终保留表对应的语义模型，避免无关上下文膨胀 prompt。
					List<String> tableNames = result.getTable().stream().map(TableDTO::getName).toList();
					List<SemanticModel> semanticModels = semanticModelService
						.getByAgentIdAndTableNames(Long.valueOf(agentIdStr), tableNames);

					String semanticModelPrompt = buildSemanticModelPrompt(semanticModels);
					resultMap.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, semanticModelPrompt);
				});

		// 这里主动拼接一组“过程消息”，让前端知道当前处于哪一步，而不是只看到最终结果。
		Flux<ChatResponse> preFlux = Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("开始构建初始 Schema..."));
			emitter.next(ChatResponseUtil.createResponse("初始 Schema 构建完成。"));
			emitter.complete();
		});
		Flux<ChatResponse> displayFlux = preFlux.concatWith(schemaFlux).concatWith(Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("开始处理 Schema 选择..."));
			emitter.next(ChatResponseUtil.createResponse("Schema 选择处理完成。"));
			emitter.complete();
		}));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> resultMap, displayFlux);

		// 这里除了返回 generator，也同步直接回写几个关键状态，确保后续节点第一时间可读到方言和重试控制信息。
		return Map.of(TABLE_RELATION_OUTPUT, generator, DB_DIALECT_TYPE, agentDbConfig.getDialectType(),
				TABLE_RELATION_RETRY_COUNT, 0, TABLE_RELATION_EXCEPTION_OUTPUT, "");
	}

	/**
	 * 基于召回文档构建初始 Schema。
	 *
	 * 这里构建的是“待精筛版 Schema”：
	 * - 先用文档恢复表、字段、数据库名等结构。
	 * - 再把逻辑外键补进去，提升 JOIN 信息完整性。
	 */
	private SchemaDTO buildInitialSchema(String agentId, List<Document> columnDocuments, List<Document> tableDocuments,
			DbConfigBO agentDbConfig, List<String> logicalForeignKeys) {
		// 创建空 Schema 容器，下面分阶段填充数据库、表、列和外键。
		SchemaDTO schemaDTO = new SchemaDTO();

		// 从数据源配置写入数据库/schema 名称。
		schemaService.extractDatabaseName(schemaDTO, agentDbConfig);

		// 根据召回 Document metadata 恢复表和字段结构。
		schemaService.buildSchemaFromDocuments(agentId, columnDocuments, tableDocuments, schemaDTO);

		// 数据库真实外键之外，还要合并业务侧维护的逻辑关系。
		if (logicalForeignKeys != null && !logicalForeignKeys.isEmpty()) {
			List<String> existingForeignKeys = schemaDTO.getForeignKeys();
			if (existingForeignKeys == null || existingForeignKeys.isEmpty()) {
				// 没有物理外键时直接使用逻辑外键列表。
				schemaDTO.setForeignKeys(logicalForeignKeys);
			}
			else {
				// 同时存在物理和逻辑外键时复制后合并，避免修改原列表。
				List<String> allForeignKeys = new ArrayList<>(existingForeignKeys);
				allForeignKeys.addAll(logicalForeignKeys);
				schemaDTO.setForeignKeys(allForeignKeys);
			}
			log.info("Merged {} logical foreign keys into schema for agent: {}", logicalForeignKeys.size(), agentId);
		}

		// 返回待模型精筛的完整初始 Schema。
		return schemaDTO;
	}

	/**
	 * 在初始 Schema 基础上继续做精筛。
	 *
	 * `schemaAdvice` 来源于上一次 SQL 生成失败后的补充建议。
	 * 如果存在该建议，就把它带入本次选表过程，让模型根据失败经验调整保留表集合。
	 */
	private Flux<ChatResponse> processSchemaSelection(SchemaDTO schemaDTO, String input, String evidence,
			OverAllState state, DbConfigBO agentDbConfig, Consumer<SchemaDTO> dtoConsumer) {
		// SQL 生成阶段发现 Schema 缺失时会把补充建议写回该状态。
		String schemaAdvice = StateUtil.getStringValue(state, SQL_GENERATE_SCHEMA_MISSING_ADVICE, null);

		// fineSelect 返回模型流，并通过 dtoConsumer 交付最终解析后的 SchemaDTO。
		Flux<ChatResponse> schemaFlux;
		if (schemaAdvice != null) {
			log.info("[{}] Processing with schema supplement advice: {}", this.getClass().getSimpleName(),
					schemaAdvice);
			schemaFlux = nl2SqlService.fineSelect(schemaDTO, input, evidence, schemaAdvice, agentDbConfig, dtoConsumer);
		}
		else {
			log.info("[{}] Executing regular schema selection", this.getClass().getSimpleName());
			schemaFlux = nl2SqlService.fineSelect(schemaDTO, input, evidence, null, agentDbConfig, dtoConsumer);
		}

		// 在真实模型输出前后添加提示和 JSON 边界标记。
		return Flux
			.just(ChatResponseUtil.createResponse("正在选择合适的数据表...\n"),
					ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign()))
			.concatWith(schemaFlux)
			.concatWith(Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
					ChatResponseUtil.createResponse("\n\n选择数据表完成。")));
	}

	/**
	 * 获取与当前召回表相关的逻辑外键。
	 *
	 * 一个数据源可能配置了大量逻辑关系，如果全部塞入 Prompt，只会增加噪声。
	 * 因此这里只保留“源表或目标表命中了当前召回集合”的关系。
	 */
	private List<String> getLogicalForeignKeys(Long agentId, List<Document> tableDocuments) {
		try {
			AgentDatasource agentDatasource = agentDatasourceService.getCurrentAgentDatasource(agentId);
			if (agentDatasource == null || agentDatasource.getDatasourceId() == null) {
				log.warn("No active datasource found for agent: {}", agentId);
				return Collections.emptyList();
			}

			Integer datasourceId = agentDatasource.getDatasourceId();

			Set<String> recalledTableNames = tableDocuments.stream()
				.map(doc -> (String) doc.getMetadata().get("name"))
				.filter(name -> name != null && !name.isEmpty())
				.collect(Collectors.toSet());

			log.info("Recalled table names for agent {}: {}", agentId, recalledTableNames);

			List<LogicalRelation> allLogicalRelations = datasourceService.getLogicalRelations(datasourceId);
			log.info("Found {} logical relations in datasource: {}", allLogicalRelations.size(), datasourceId);

			List<String> formattedForeignKeys = allLogicalRelations.stream()
				.filter(lr -> recalledTableNames.contains(lr.getSourceTableName())
						|| recalledTableNames.contains(lr.getTargetTableName()))
				.map(lr -> String.format("%s.%s=%s.%s", lr.getSourceTableName(), lr.getSourceColumnName(),
						lr.getTargetTableName(), lr.getTargetColumnName()))
				.distinct()
				.collect(Collectors.toList());

			log.info("Filtered {} relevant logical relations for recalled tables", formattedForeignKeys.size());
			return formattedForeignKeys;
		}
		catch (Exception e) {
			log.error("Error fetching logical foreign keys for agent: {}", agentId, e);
			return Collections.emptyList();
		}
	}

}
