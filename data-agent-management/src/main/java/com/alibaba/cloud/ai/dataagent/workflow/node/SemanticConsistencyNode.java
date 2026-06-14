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

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SemanticConsistencyDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
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

import static com.alibaba.cloud.ai.dataagent.constant.Constant.DB_DIALECT_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SEMANTIC_CONSISTENCY_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.prompt.PromptHelper.buildMixMacSqlDbPrompt;
import static com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

/**
 * 语义一致性校验节点。
 *
 * SQL 生成完并不代表可以直接执行。除了语法正确，系统还需要确认：
 * - 这条 SQL 是否真的在回答当前用户问题。
 * - 字段、过滤条件、聚合逻辑是否与召回到的 Schema 和证据一致。
 * - 当前 SQL 是否符合执行计划中这一步应完成的任务。
 *
 * 这一步相当于 SQL 执行前的最后一道语义闸门。
 * 如果校验不通过，下游 Dispatcher 会把流程重新路由回 SQL 生成节点。
 */
@Slf4j
@Component
@AllArgsConstructor
public class SemanticConsistencyNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	/**
	 * 执行语义一致性校验。
	 *
	 * 这里校验的重点不是 SQL 语法，而是“SQL 与问题是否语义对齐”。
	 * 例如语法完全正确，但查错了表、漏了条件，依然会在这里被判定为不通过。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// EvidenceRecallNode 找到的业务规则和术语证据。
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		// TableRelationNode 整理出的候选表、字段和关联关系。
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		// 方言决定 SQL 函数、分页等语法应遵循哪种数据库规则。
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
		// SqlGenerateNode 刚生成、尚未执行的 SQL。
		String sql = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
		// 规范化问题是判断 SQL 是否真正回答用户需求的基准。
		String userQuery = StateUtil.getCanonicalQuery(state);

		// 把所有校验上下文一次性打包到 DTO，避免后续服务层依赖零散参数。
		SemanticConsistencyDTO semanticConsistencyDTO = SemanticConsistencyDTO.builder()
			.dialect(dialect)
			.sql(sql)
			.executionDescription(getCurrentExecutionStepInstruction(state))
			.schemaInfo(buildMixMacSqlDbPrompt(schemaDTO, true))
			.userQuery(userQuery)
			.evidence(evidence)
			.build();
		log.info("Starting semantic consistency validation - SQL: {}", sql);

		// 调用 NL2SQL 服务中的语义校验模型，结果仍以 token 流返回。
		Flux<ChatResponse> validationResultFlux = nl2SqlService.performSemanticConsistency(semanticConsistencyDTO);

		// 返回的 generator 会在前端展示校验过程，并在流结束后将结果落入 state。
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始语义一致性校验。", "语义一致性校验完成。", validationResult -> {
					// 这里依赖提示词约定：以“不通过”开头时表示需要重新生成 SQL。
					boolean isPassed = !validationResult.startsWith("不通过");
					// 把布尔结果和可选重试原因一起返回给下一跳 Dispatcher。
					Map<String, Object> result = buildValidationResult(isPassed, validationResult);
					log.info("[{}] Semantic consistency validation result: {}, passed: {}",
							this.getClass().getSimpleName(), validationResult, isPassed);
					return result;
				}, validationResultFlux);

		// 节点输出 generator，校验文本会在订阅后流式发送。
		return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, generator);
	}

	/**
	 * 把校验结果翻译成 Graph 状态。
	 *
	 * 通过时只需要写一个布尔值给 Dispatcher。
	 * 不通过时还要把失败原因转成 `SqlRetryDto`，供下一轮 SQL 生成作为修复提示。
	 */
	private Map<String, Object> buildValidationResult(boolean passed, String validationResult) {
		// 通过时只需写入 true，Dispatcher 会把流程送往 SQL 执行。
		if (passed) {
			return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, true);
		}
		else {
			// 不通过时保留模型给出的具体原因，下一轮 SQL 生成会把它加入修复 Prompt。
			return Map.of(SEMANTIC_CONSISTENCY_NODE_OUTPUT, false, SQL_REGENERATE_REASON,
					SqlRetryDto.semantic(validationResult));
		}
	}

}
