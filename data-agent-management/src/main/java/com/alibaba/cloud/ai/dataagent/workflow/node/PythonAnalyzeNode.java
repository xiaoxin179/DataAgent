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

import com.alibaba.cloud.ai.dataagent.prompt.PromptConstant;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_ANALYSIS_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_FALLBACK_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;

/**
 * Python 结果分析节点。
 *
 * 当 Python 执行完成后，系统通常不会把原始 JSON/文本直接原样展示给用户，
 * 而是让模型再做一层“解释型总结”，把程序输出转成自然语言结论。
 *
 * 这个节点承担的就是“把 Python 输出解释成人能直接读懂的分析结论”。
 */
@Slf4j
@Component
@AllArgsConstructor
public class PythonAnalyzeNode implements NodeAction {

	private final LlmService llmService;

	/**
	 * 分析 Python 执行结果，并把分析结论挂回当前步骤的执行结果集合里。
	 *
	 * 状态存储约定：
	 * - `SQL_EXECUTE_NODE_OUTPUT` 在这里被当作“各步骤结果汇总表”使用。
	 * - 本节点会以 `step_x_analysis` 这样的 key 写入当前步骤的分析文字。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 读取规范化用户问题，确保分析结论围绕原始业务目标。
		String userQuery = StateUtil.getCanonicalQuery(state);
		// PythonExecuteNode 写入的 stdout 或结构化执行结果。
		String pythonOutput = StateUtil.getStringValue(state, PYTHON_EXECUTE_NODE_OUTPUT);
		// 当前步骤编号用于把分析结果写到 step_n_analysis。
		int currentStep = PlanProcessUtil.getCurrentStepNumber(state);

		@SuppressWarnings("unchecked")
		// 复制前先读取此前所有步骤结果；没有结果时使用空 Map。
		Map<String, String> sqlExecuteResult = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class,
				new HashMap<>());

		// 执行服务不可用时，PythonExecuteNode 会打开降级标记。
		boolean isFallbackMode = StateUtil.getObjectValue(state, PYTHON_FALLBACK_MODE, Boolean.class, false);

		// 降级分支不再调用 LLM，直接生成固定说明，避免错误继续扩散。
		if (isFallbackMode) {
			// 降级模式下不再继续做复杂分析，而是给出一条固定兜底消息，让整体工作流还能继续向前推进。
			String fallbackMessage = "Python 高级分析功能暂时不可用，执行阶段出现错误。";
			log.warn("Python 分析节点检测到降级模式，返回固定提示信息。");

			// 把固定文本包装成单元素 Flux，以复用正常的流式输出处理。
			Flux<ChatResponse> fallbackFlux = Flux.just(ChatResponseUtil.createResponse(fallbackMessage));

			// 流结束时复制旧结果、追加本步骤说明，并把计划步骤推进一位。
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state, "正在处理分析结果...\n", "\n处理完成。", aiResponse -> {
						// 使用副本避免直接修改从 Graph state 读取到的共享 Map。
						Map<String, String> updatedSqlResult = new HashMap<>(sqlExecuteResult);
						updatedSqlResult.put("step_" + currentStep + "_analysis", fallbackMessage);
						log.info("Python fallback message: {}", fallbackMessage);
						return Map.of(SQL_EXECUTE_NODE_OUTPUT, updatedSqlResult, PLAN_CURRENT_STEP, currentStep + 1);
					}, fallbackFlux);

			// 降级和正常模式使用相同的节点输出 key，Dispatcher 无需区分内部实现。
			return Map.of(PYTHON_ANALYSIS_NODE_OUTPUT, generator);
		}

		// 正常模式把 Python 输出和用户目标渲染进分析 Prompt。
		String systemPrompt = PromptConstant.getPythonAnalyzePromptTemplate()
			.render(Map.of("python_output", pythonOutput, "user_query", userQuery));

		// callSystem 表示整个 Prompt 作为系统指令发送。
		Flux<ChatResponse> pythonAnalyzeFlux = llmService.callSystem(systemPrompt);

		// 一边向前端输出分析文本，一边在结束时保存完整结果并推进计划。
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "正在分析代码运行结果...\n", "\n结果分析完成。", aiResponse -> {
					// 不修改旧 Map，创建包含本轮结果的新状态值。
					Map<String, String> updatedSqlResult = new HashMap<>(sqlExecuteResult);
					updatedSqlResult.put("step_" + currentStep + "_analysis", aiResponse);
					log.info("Python analyze result: {}", aiResponse);
					return Map.of(SQL_EXECUTE_NODE_OUTPUT, updatedSqlResult, PLAN_CURRENT_STEP, currentStep + 1);
				}, pythonAnalyzeFlux);

		// Graph 识别到 Flux 后会订阅它并持续产生 StreamingOutput。
		return Map.of(PYTHON_ANALYSIS_NODE_OUTPUT, generator);
	}

}
