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
import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.DB_DIALECT_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

/**
 * SQL 生成节点。
 *
 * 这个节点的职责是根据当前执行步骤的 instruction 生成一条可执行 SQL。
 * 它不负责真正执行 SQL，执行工作由 `SqlExecuteNode` 完成。
 *
 * 它需要处理三种不同场景：
 * 1. 首次生成 SQL。
 * 2. SQL 执行失败后的修复生成。
 * 3. 语义一致性校验失败后的修复生成。
 *
 * 这也是为什么它要消费 `SQL_REGENERATE_REASON`：该字段决定当前是“首生”还是“带错误上下文修复”。
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlGenerateNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	private final DataAgentProperties properties;

	/**
	 * 生成当前步骤所需 SQL。
	 *
	 * 注意这里面向的是“当前计划步骤”，而不是整个用户问题。
	 * 这意味着多步分析任务会被拆成多轮“单步生成 SQL”，而不是一次性把全部诉求压给模型。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 统计当前计划步骤已经生成过多少版 SQL。
		int count = state.value(SQL_GENERATE_COUNT, 0);

		// 达到上限后不再调用模型，输出原因并写入 END 标记。
		if (count >= properties.getMaxSqlRetryCount()) {
			// 读取当前计划步骤，用于给用户展示是哪一步失败。
			ExecutionStep executionStep = PlanProcessUtil.getCurrentExecutionStep(state);
			String sqlGenerateOutput = String.format(
					"步骤[%d]中，SQL 生成重试次数已达到上限。最大重试次数：%d，当前已尝试次数：%d，当前步骤内容：\n%s",
					executionStep.getStep(), properties.getMaxSqlRetryCount(), count,
					executionStep.getToolParameters().getInstruction());
			log.error("SQL generation failed, reason: {}", sqlGenerateOutput);

			// 构造一条只包含失败说明的展示流。
			Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(sqlGenerateOutput));

			// 流结束后将 SQL 输出设为 END，并重置计数。
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state, "正在进行重试评估...", "重试评估完成",
					retryOutput -> Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT, 0), preFlux);
			return Map.of(SQL_GENERATE_OUTPUT, generator);
		}

		// Planner 已经把当前步骤的任务说明写进状态，这里只需要关心“这一步要做什么 SQL”。
		String promptForSql = getCurrentExecutionStepInstruction(state);

		// displayMessage 告诉前端本轮是首次生成还是修复生成。
		String displayMessage;
		Flux<String> sqlFlux;

		// 结构化重试 DTO 区分数据库执行失败和语义校验失败。
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());

		// 数据库执行失败时，把旧 SQL、数据库错误和步骤说明一起交给模型修复。
		if (retryDto.sqlExecuteFail()) {
			displayMessage = "检测到 SQL 执行异常，开始重新生成 SQL...";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else if (retryDto.semanticFail()) {
			// 语义失败时使用相同修复入口，但错误原因来自 SemanticConsistencyNode。
			displayMessage = "语义一致性校验未通过，开始重新生成 SQL...";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else {
			// 没有失败原因表示首次生成。
			displayMessage = "开始生成 SQL...";
			sqlFlux = handleGenerateSql(state, promptForSql);
		}

		// 先准备默认失败状态；只有模型流正常结束后才会用实际 SQL 覆盖 END。
		Map<String, Object> result = new HashMap<>(Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT,
				count + 1, SQL_REGENERATE_REASON, SqlRetryDto.empty()));

		// 展示流和最终状态回写拆开处理：
		// - 用户先看到生成提示和 SQL 文本。
		// - 流结束后再统一 trim 并写回最终 SQL。
		StringBuilder sqlCollector = new StringBuilder();
		// 在 SQL 前发送提示和类型起始标记。
		Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(displayMessage),
				ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
		// 每个 SQL token 一边进入 collector，一边转成 ChatResponse 发给前端。
		Flux<ChatResponse> displayFlux = preFlux
			.concatWith(sqlFlux.doOnNext(sqlCollector::append).map(ChatResponseUtil::createPureResponse))
			.concatWith(Flux.just(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()),
					ChatResponseUtil.createResponse("SQL 生成完成，准备执行。")));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> {
					// 模型流完成后清理代码块和首尾空白，得到最终可执行 SQL。
					String sql = nl2SqlService.sqlTrim(sqlCollector.toString());
					result.put(SQL_GENERATE_OUTPUT, sql);
					return result;
				}, displayFlux);

		return Map.of(SQL_GENERATE_OUTPUT, generator);
	}

	/**
	 * 基于旧 SQL 与错误原因生成修复版 SQL。
	 *
	 * 这条路径统一覆盖执行失败与语义失败两种重生场景。
	 */
	private Flux<String> handleRetryGenerateSql(OverAllState state, String originalSql, String errorMsg,
			String executionDescription) {
		// 生成 SQL 所需的业务证据。
		String evidence = StateUtil.getStringValue(state, EVIDENCE);

		// 经过召回和关系整理后的结构化 Schema。
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);

		// 消除多轮指代后的标准用户问题。
		String userQuery = StateUtil.getCanonicalQuery(state);

		// 数据库方言影响函数、分页和标识符语法。
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);

		// 把首次生成和重试生成需要的全部上下文封装成统一 DTO。
		SqlGenerationDTO sqlGenerationDTO = SqlGenerationDTO.builder()
			.evidence(evidence)
			.query(userQuery)
			.schemaDTO(schemaDTO)
			.sql(originalSql)
			.exceptionMessage(errorMsg)
			.executionDescription(executionDescription)
			.dialect(dialect)
			.build();

		// Nl2SqlService 返回字符串 token 流，当前节点负责包装为 Graph 流。
		return nl2SqlService.generateSql(sqlGenerationDTO);
	}

	/**
	 * 首次生成 SQL。
	 *
	 * 本质上还是复用重试生成逻辑，只是不给原 SQL 和错误信息。
	 */
	private Flux<String> handleGenerateSql(OverAllState state, String executionDescription) {
		return handleRetryGenerateSql(state, null, null, executionDescription);
	}

}
