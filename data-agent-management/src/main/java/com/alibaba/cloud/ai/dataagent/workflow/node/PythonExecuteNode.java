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

import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.service.code.CodePoolExecutorService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_FALLBACK_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_GENERATE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_IS_SUCCESS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_TRIES_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;

/**
 * Python 执行节点。
 *
 * 这一层真正把模型生成的 Python 代码交给代码执行池运行，并把执行结果写回 Graph 状态。
 *
 * 为什么系统要把“生成代码”和“执行代码”拆成两个节点：
 * - 生成阶段只负责让模型产出代码。
 * - 执行阶段负责与代码沙箱/执行池集成，处理 stdout、stderr、异常、重试和降级。
 * - 分层之后，失败重试策略会清晰很多。
 */
@Slf4j
@Component
public class PythonExecuteNode implements NodeAction {

	private final CodePoolExecutorService codePoolExecutor;

	private final ObjectMapper objectMapper;

	private final JsonParseUtil jsonParseUtil;

	private final CodeExecutorProperties codeExecutorProperties;

	public PythonExecuteNode(CodePoolExecutorService codePoolExecutor, JsonParseUtil jsonParseUtil,
			CodeExecutorProperties codeExecutorProperties) {
		// 保存统一代码执行服务，具体实现可以是本地进程、Docker 或模拟器。
		this.codePoolExecutor = codePoolExecutor;

		// 复用项目统一 ObjectMapper，确保 JSON 配置一致。
		this.objectMapper = JsonUtil.getObjectMapper();
		this.jsonParseUtil = jsonParseUtil;
		this.codeExecutorProperties = codeExecutorProperties;
	}

	/**
	 * 执行 Python 代码，并把 stdout 或失败信息写回状态。
	 *
	 * 关键输出字段：
	 * - `PYTHON_EXECUTE_NODE_OUTPUT`：标准输出或错误信息。
	 * - `PYTHON_IS_SUCCESS`：本轮执行是否成功。
	 * - `PYTHON_FALLBACK_MODE`：当重试次数耗尽后，是否进入降级模式。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		try {
			// 读取上一节点生成的 Python 代码。
			String pythonCode = StateUtil.getStringValue(state, PYTHON_GENERATE_NODE_OUTPUT);

			// Python 代码的数据输入来自最近一次 SQL 查询的结构化行列表。
			List<Map<String, String>> sqlResults = StateUtil.hasValue(state, SQL_RESULT_LIST_MEMORY)
					? StateUtil.getListValue(state, SQL_RESULT_LIST_MEMORY) : new ArrayList<>();

			// 当前次数用于判断失败后是继续重试还是进入 fallback。
			int triesCount = StateUtil.getObjectValue(state, PYTHON_TRIES_COUNT, Integer.class, 0);

			// 将代码和 SQL 数据序列化为执行器统一请求。
			CodePoolExecutorService.TaskRequest taskRequest = new CodePoolExecutorService.TaskRequest(pythonCode,
					objectMapper.writeValueAsString(sqlResults), null);

			// `CodePoolExecutorService` 是真正对接代码运行容器/执行池的服务。
			// 它返回标准输出、标准错误和异常信息，供上层统一处理。
			CodePoolExecutorService.TaskResponse taskResponse = this.codePoolExecutor.runTask(taskRequest);

			// 执行器会统一汇总退出状态、stdout、stderr 和异常信息。
			if (!taskResponse.isSuccess()) {
				String errorMsg = "Python Execute Failed!\nStdOut: " + taskResponse.stdOut() + "\nStdErr: "
						+ taskResponse.stdErr() + "\nExceptionMsg: " + taskResponse.exceptionMsg();
				log.error(errorMsg);

				// 达到最大重试次数后，不再继续回生成节点，而是进入降级模式，让后续分析节点给出兜底反馈。
				if (triesCount >= codeExecutorProperties.getPythonMaxTriesCount()) {
					log.error("Python 执行失败且已超过最大重试次数（已尝试次数：{}），启动降级兜底逻辑。错误信息: {}", triesCount,
							errorMsg);

					// 使用空 JSON 作为可解析的降级输出，让下游分析节点仍能继续。
					String fallbackOutput = "{}";

					// 构造只用于前端说明降级状态的展示流。
					Flux<ChatResponse> fallbackDisplayFlux = Flux.create(emitter -> {
						emitter.next(ChatResponseUtil.createResponse("开始执行 Python 代码..."));
						emitter.next(ChatResponseUtil.createResponse("Python 代码执行失败且已超过最大重试次数，采用降级策略继续处理。"));
						emitter.complete();
					});

					// 流完成后写入失败状态和 fallback 标记。
					Flux<GraphResponse<StreamingOutput>> fallbackGenerator = FluxUtil
						.createStreamingGeneratorWithMessages(this.getClass(), state,
								v -> Map.of(PYTHON_EXECUTE_NODE_OUTPUT, fallbackOutput, PYTHON_IS_SUCCESS, false,
										PYTHON_FALLBACK_MODE, true),
								fallbackDisplayFlux);

					// 交给 Graph 消费展示流并合并最终状态。
					return Map.of(PYTHON_EXECUTE_NODE_OUTPUT, fallbackGenerator);
				}

				// 未达到上限时抛出异常，下面统一转换为可重试的错误状态。
				throw new RuntimeException(errorMsg);
			}

			// Python 代码 stdout 常常是 JSON。
			// 这里先尝试反序列化再重新序列化，目的是把 Unicode 转义等格式统一成更易读的输出。
			String stdout = taskResponse.stdOut();
			Object value = jsonParseUtil.tryConvertToObject(stdout, Object.class);
			if (value != null) {
				stdout = objectMapper.writeValueAsString(value);
			}
			// Lambda 捕获的局部变量必须是 final 或 effectively final。
			String finalStdout = stdout;

			log.info("Python Execute Success! StdOut: {}", finalStdout);

			// 将标准输出放在 JSON 类型边界中流式展示。
			Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
				emitter.next(ChatResponseUtil.createResponse("开始执行 Python 代码..."));
				emitter.next(ChatResponseUtil.createResponse("标准输出："));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign()));
				emitter.next(ChatResponseUtil.createResponse(finalStdout));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()));
				emitter.next(ChatResponseUtil.createResponse("Python 代码执行成功。"));
				emitter.complete();
			});

			// 正常完成后把 stdout 和成功标记写回 state。
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state,
					v -> Map.of(PYTHON_EXECUTE_NODE_OUTPUT, finalStdout, PYTHON_IS_SUCCESS, true), displayFlux);

			return Map.of(PYTHON_EXECUTE_NODE_OUTPUT, generator);
		}
		catch (Exception e) {
			// 包括执行失败、stdout 解析失败等异常都会进入统一失败分支。
			String errorMessage = e.getMessage();
			log.error("Python Execute Exception: {}", errorMessage);

			// 保存错误文本和失败标记，Dispatcher 会结合次数决定是否重新生成代码。
			Map<String, Object> errorResult = Map.of(PYTHON_EXECUTE_NODE_OUTPUT, errorMessage, PYTHON_IS_SUCCESS,
					false);

			// 同步向前端说明本次代码执行失败。
			Flux<ChatResponse> errorDisplayFlux = Flux.create(emitter -> {
				emitter.next(ChatResponseUtil.createResponse("开始执行 Python 代码..."));
				emitter.next(ChatResponseUtil.createResponse("Python 代码执行失败: " + errorMessage));
				emitter.complete();
			});

			// displayFlux 完成后返回预先构造的 errorResult。
			var generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(), state, v -> errorResult,
					errorDisplayFlux);

			return Map.of(PYTHON_EXECUTE_NODE_OUTPUT, generator);
		}
	}

}
