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
package com.alibaba.cloud.ai.dataagent.service.graph;

import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.MultiTurnContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.StreamContext;
import com.alibaba.cloud.ai.dataagent.service.langfuse.LangfuseService;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import com.alibaba.cloud.ai.dataagent.workflow.node.PlannerNode;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.opentelemetry.api.trace.Span;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_FEEDBACK_DATA;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_FEEDBACK_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_REVIEW_ENABLED;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.IS_ONLY_NL2SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_ERROR;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

/**
 * Graph 工作流执行服务。
 *
 * 这是 `GraphController` 和 `StateGraph` 之间的桥接层，负责把一次 HTTP 流式请求转换成真正的图执行。
 * 它承接了三类关键职责：
 * 1. 创建、恢复和销毁一次流式会话的上下文。
 * 2. 把 Graph 节点流式输出适配成前端可消费的 SSE 事件。
 * 3. 处理取消、失败、完成三种生命周期，并做好 tracing 收尾。
 *
 * 关键框架 API：
 * - {@link StateGraph} / {@link CompiledGraph}：
 *   前者描述工作流拓扑，后者是可实际运行的编译结果。
 * - {@link RunnableConfig}：
 *   每次图执行的运行时配置，常用于传递 threadId、metadata、resume 状态。
 * - {@link Flux}：
 *   Reactor 响应式流类型，Graph 节点的流式输出和 SSE 返回都建立在它之上。
 * - {@link Sinks.Many}：
 *   向响应式流手动推送事件的入口，适合把异步工作流输出桥接到 HTTP 连接。
 */
@Slf4j
@Service
public class GraphServiceImpl implements GraphService {

	private final CompiledGraph compiledGraph;

	// 专门用于异步建立 Graph 订阅，避免占用处理 HTTP 请求的线程。
	private final ExecutorService executor;

	/**
	 * 一个 threadId 对应一次流式会话上下文。
	 * 里面保存：
	 * - 当前 SSE sink
	 * - Reactor 订阅句柄
	 * - 文本类型状态
	 * - 已累计输出
	 * - tracing span
	 */
	private final ConcurrentHashMap<String, StreamContext> streamContextMap = new ConcurrentHashMap<>();

	private final MultiTurnContextManager multiTurnContextManager;

	private final LangfuseService langfuseReporter;

	public GraphServiceImpl(StateGraph stateGraph, ExecutorService executorService,
			MultiTurnContextManager multiTurnContextManager, LangfuseService langfuseReporter)
			throws GraphStateException {
		// `interruptBefore(HUMAN_FEEDBACK_NODE)` 的含义是：
		// 工作流执行到人工反馈节点前先暂停，把继续权交给外部请求。
		// 这样前端就可以先展示计划，再决定批准、修改或拒绝它。
		this.compiledGraph = stateGraph.compile(CompileConfig.builder().interruptBefore(HUMAN_FEEDBACK_NODE).build());
		this.executor = executorService;
		this.multiTurnContextManager = multiTurnContextManager;
		this.langfuseReporter = langfuseReporter;
	}

	/**
	 * 以同步方式运行图，并直接返回最终 SQL。
	 *
	 * 适用场景：
	 * - MCP 工具调用
	 * - 只关心最终 SQL，不关心中间推理过程的接口
	 *
	 * 与 `graphStreamProcess(...)` 的区别是：
	 * - 这里走 `invoke(...)`，拿一次性最终结果。
	 * - SSE 主链路走 `stream(...)`，边执行边产出。
	 */
	@Override
	public String nl2sql(String naturalQuery, String agentId) throws GraphRunnerException {
		// 使用精简模式构造初始 state，并同步运行整张图直到 END。
		OverAllState state = compiledGraph
			.invoke(Map.of(IS_ONLY_NL2SQL, true, INPUT_KEY, naturalQuery, AGENT_ID, agentId),
					RunnableConfig.builder().build())
			.orElseThrow();

		// 从最终 state 中取出 SQL；如果节点没有写入该 key，则返回空字符串。
		return state.value(SQL_GENERATE_OUTPUT, "");
	}

	/**
	 * 发起一次图的流式执行。
	 *
	 * 执行步骤：
	 * 1. 生成或确认 threadId。
	 * 2. 初始化当前 thread 对应的 `StreamContext`。
	 * 3. 根据是否带 `humanFeedbackContent` 判断是新请求还是“从暂停点继续执行”。
	 *
	 * 这里不直接返回业务结果，而是通过传入的 `sink` 持续推送中间和最终事件。
	 */
	@Override
	public void graphStreamProcess(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, GraphRequest graphRequest) {
		// 首次请求可能没有 threadId，此时由后端生成，用于绑定 Graph 状态和 SSE 上下文。
		if (!StringUtils.hasText(graphRequest.getThreadId())) {
			graphRequest.setThreadId(UUID.randomUUID().toString());
		}

		// 后续所有上下文查找都使用同一个 threadId。
		String threadId = graphRequest.getThreadId();

		// 一个 threadId 只能对应一个活跃的流上下文；重复请求会复用同一个上下文对象。
		StreamContext context = streamContextMap.computeIfAbsent(threadId, k -> new StreamContext());

		// 保存 Controller 创建的 sink，Graph 的异步输出稍后通过它推送给浏览器。
		context.setSink(sink);

		// 带有效反馈文本表示这是对暂停流程的续跑请求。
		if (StringUtils.hasText(graphRequest.getHumanFeedbackContent())) {
			handleHumanFeedback(graphRequest);
		}
		else {
			// 新请求会先进入这里，先把当前问题登记为“正在进行中的轮次”。
			// 这样后续图节点在流式输出时，就能把 Planner 的 chunk 逐段追加进去。
			handleNewProcess(graphRequest);
		}
	}

	/**
	 * 主动停止指定 thread 的流式处理。
	 *
	 * 最常见触发点：
	 * - 浏览器取消订阅
	 * - 网络断开
	 * - Sink 发流失败
	 *
	 * 为什么这里优先 `remove`：
	 * - `ConcurrentHashMap.remove(...)` 可以保证只有一个线程成功拿到上下文并执行清理。
	 * - 这样能避免重复释放订阅、重复结束 span。
	 */
	@Override
	public void stopStreamProcessing(String threadId) {
		// 没有会话标识时无法定位任务，直接返回。
		if (!StringUtils.hasText(threadId)) {
			return;
		}

		// 记录主动停止动作，便于关联浏览器断开或 sink 发射失败。
		log.info("Stopping stream processing for threadId: {}", threadId);

		// 未完成的 Planner 输出不能进入正式多轮历史，因此先删除 pending turn。
		multiTurnContextManager.discardPending(threadId);

		// 原子地从活动任务表移除上下文，只有成功移除的线程负责实际清理。
		StreamContext context = streamContextMap.remove(threadId);
		if (context != null) {
			// 用户主动断开不一定是失败，更常见是前端离开页面，因此这里按成功关闭 span。
			if (context.getSpan() != null && context.getSpan().isRecording()) {
				langfuseReporter.endSpanSuccess(context.getSpan(), threadId, context.getCollectedOutput());
			}
			context.cleanup();
			log.info("Cleaned up stream context for threadId: {}", threadId);
		}
	}

	/**
 * `handleNewProcess`：处理当前阶段的一次业务分支或中间结果。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	private void handleNewProcess(GraphRequest graphRequest) {
		// 读取 Graph 启动所需的业务参数。
		String query = graphRequest.getQuery();
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();

		// nl2sqlOnly 控制是否在得到 SQL 后直接结束，不再执行 Python 和报告。
		boolean nl2sqlOnly = graphRequest.isNl2sqlOnly();

		// 纯 NL2SQL 模式没有完整计划审核环节，因此强制关闭人工审核。
		boolean humanReviewEnabled = graphRequest.isHumanFeedback() & !(nl2sqlOnly);

		// 新流程必须能定位会话、智能体和用户问题。
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(query)) {
			throw new IllegalArgumentException("Invalid arguments");
		}

		// graphStreamProcess 已经创建上下文并写入 sink，这里重新取出用于启动和收尾。
		StreamContext context = streamContextMap.get(threadId);

		// 缺失上下文说明调用顺序错误，继续执行会导致节点输出无处发送。
		if (context == null || context.getSink() == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}

		// 与取消请求并发时，上下文可能已被清理，此时不能重新启动 Graph。
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}

		// 创建本次完整流式分析的追踪 span，并保存在上下文中供完成/错误路径结束。
		Span span = langfuseReporter.startLLMSpan("graph-stream", graphRequest);
		context.setSpan(span);

		// 先读取当前 threadId 已经积累的历史上下文，再把这一轮新问题登记进去。
		// 这样 buildContext(...) 返回的是“上一轮历史”，而 beginTurn(...) 负责准备“这一轮草稿”。
		String multiTurnContext = multiTurnContextManager.buildContext(threadId);
		multiTurnContextManager.beginTurn(threadId, query);

		// 这里启动真正的图执行。
		// Map.of(...) 里放的是本轮运行所需的全部输入：
		// - query：用户问题
		// - agentId：当前智能体
		// - humanReviewEnabled：是否允许人工审核
		// - multiTurnContext：上一轮历史上下文
		// - threadId：链路追踪和流式会话标识
		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(
				Map.of(IS_ONLY_NL2SQL, nl2sqlOnly, INPUT_KEY, query, AGENT_ID, agentId, HUMAN_REVIEW_ENABLED,
						humanReviewEnabled, MULTI_TURN_CONTEXT, multiTurnContext, TRACE_THREAD_ID, threadId),
				RunnableConfig.builder().threadId(threadId).build());
		// 图开始流式输出后，把每个节点事件订阅起来，转发给前端 SSE。
		// 订阅过程里还会保存 Disposable，方便前端断开时及时取消后台任务。
		subscribeToFlux(context, nodeOutputFlux, graphRequest, agentId, threadId);
	}

	/**
	 * 处理人工反馈后的续跑。
	 *
	 * 这条路径和全新请求最大的不同是：
	 * - 它不会从头新建图状态；
	 * - 而是通过 `updateState(...)` 在既有 thread 上修改状态，然后从暂停点继续。
	 */
	private void handleHumanFeedback(GraphRequest graphRequest) {
		// 取出本轮反馈所需的核心字段。
		// agentId 决定当前智能体上下文，threadId 用来定位这次会话，feedbackContent 是用户实际给出的反馈。
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();
		String feedbackContent = graphRequest.getHumanFeedbackContent();
		// 这些字段任何一个为空，都说明这次反馈请求不完整，不能继续往下跑。
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(feedbackContent)) {
			throw new IllegalArgumentException("Invalid arguments");
		}

		// 找到这次反馈对应的流上下文，里面保存着 sink、Disposable、span 等运行时状态。
		StreamContext context = streamContextMap.get(threadId);
		// 没有上下文或者没有 sink，说明当前 threadId 对应的流式会话不存在，不能继续恢复。
		if (context == null || context.getSink() == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}
		// 如果上下文已经被清理过了，说明这轮流已经结束，直接跳过即可。
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}

		// 为“人工反馈后的续跑”创建新的 tracing span，方便和前一次流式执行区分开。
		Span span = langfuseReporter.startLLMSpan("graph-feedback", graphRequest);
		context.setSpan(span);

		// 把用户对上一版计划的反馈整理成图状态里需要的结构。
		// feedback 表示是否接受计划，feedback_content 保存用户具体意见。
		Map<String, Object> feedbackData = Map.of("feedback", !graphRequest.isRejectedPlan(), "feedback_content",
				feedbackContent);
		if (graphRequest.isRejectedPlan()) {
			// 用户拒绝上一版计划时，回滚多轮上下文到本轮开始前，避免错误计划文本继续污染上下文。
			multiTurnContextManager.restartLastTurn(threadId);
		}
		// 准备要写回图状态的数据。
		// HUMAN_FEEDBACK_DATA 会被后续节点读取，MULTI_TURN_CONTEXT 会把最新上下文重新注入图里。
		Map<String, Object> stateUpdate = new HashMap<>();
		stateUpdate.put(HUMAN_FEEDBACK_DATA, feedbackData);
		stateUpdate.put(MULTI_TURN_CONTEXT, multiTurnContextManager.buildContext(threadId));

		// baseConfig 是这次 threadId 对应的基础运行配置。
		// 后面会基于它做状态更新，然后从暂停点继续执行。
		RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();
		RunnableConfig updatedConfig;
		try {
			// `updateState` 是图框架的“恢复点修改”能力，常用于 human-in-the-loop 场景。
			// 它会把 feedbackData 和 multi-turn context 写进当前线程的图状态里。
			updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to update graph state for human feedback", e);
		}
		// 在更新后的配置上补充反馈元数据，方便后续节点判断这次恢复是“人工反馈继续跑”。
		RunnableConfig resumeConfig = RunnableConfig.builder(updatedConfig)
			.addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackData)
			.build();

		// 从暂停点重新开始流式执行，但这次使用的是更新后的状态和配置。
		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
		// 继续把恢复后的节点输出订阅起来，转发给前端 SSE。
		subscribeToFlux(context, nodeOutputFlux, graphRequest, agentId, threadId);
	}

	/**
	 * 订阅 Graph 的流式输出，并把订阅句柄保存到上下文。
	 *
	 * 为什么放到 `CompletableFuture.runAsync(...)` 里：
	 * - Controller 线程不需要等待图真正开始执行。
	 * - 先把 HTTP SSE 连接建起来，再异步开始消费工作流输出，用户体验更稳定。
	 *
	 * 为什么要保存 `Disposable`：
	 * - `Disposable` 是 Reactor 的取消句柄。
	 * - 当前端断开时，可以通过它及时停止后台数据流。
	 */
	private void subscribeToFlux(StreamContext context, Flux<NodeOutput> nodeOutputFlux, GraphRequest graphRequest,
			String agentId, String threadId) {
		// 在配置的执行器中异步建立订阅，使 HTTP 请求线程可以尽快返回 SSE Flux。
		CompletableFuture.runAsync(() -> {
			// 订阅真正建立前再次检查取消状态，避免启动已被用户取消的任务。
			if (context.isCleaned()) {
				log.debug("StreamContext cleaned before subscription for threadId: {}", threadId);
				return;
			}

			// 注册三个终端回调：每个节点输出、异常终止、正常完成。
			Disposable disposable = nodeOutputFlux.subscribe(output -> handleNodeOutput(graphRequest, output),
					error -> handleStreamError(agentId, threadId, error),
					() -> handleStreamComplete(agentId, threadId));

			// 订阅建立和上下文清理可能并发发生，因此这里做原子保护。
			synchronized (context) {
				// 锁内重新检查，覆盖“subscribe 返回后、保存 disposable 前”发生取消的窗口。
				if (context.isCleaned()) {
					// 如果任务已经被取消，立即释放刚创建的订阅。
					if (disposable != null && !disposable.isDisposed()) {
						disposable.dispose();
					}
				}
				else {
					// 保存取消句柄，后续 stopStreamProcessing 可以主动停止 Graph 流。
					context.setDisposable(disposable);
				}
			}
		}, executor);
	}

	/**
	 * 处理流执行中的异常。
	 *
	 * 当前策略是：
	 * 1. 上报 tracing 失败状态。
	 * 2. 向前端发送 `error` 事件。
	 * 3. 结束 sink 并清理上下文。
	 */
	private void handleStreamError(String agentId, String threadId, Throwable error) {
		// 记录原始异常和会话标识。
		log.error("Error in stream processing for threadId: {}: ", threadId, error);

		// 从活动任务表移除上下文，阻止后续节点继续向该会话发送数据。
		StreamContext context = streamContextMap.remove(threadId);

		// 仅第一个拿到且尚未清理的调用者执行错误收尾。
		if (context != null && !context.isCleaned()) {
			// 将追踪 span 标记为失败，并保留原异常信息。
			if (context.getSpan() != null) {
				langfuseReporter.endSpanError(context.getSpan(), threadId,
						error instanceof Exception ? (Exception) error : new RuntimeException(error));
			}

			// 前端仍在线时先发送业务 error 事件，再完成 SSE 流。
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				context.getSink()
					.tryEmitNext(ServerSentEvent
						.builder(GraphNodeResponse.error(agentId, threadId,
								"Error in stream processing: " + error.getMessage()))
						.event(STREAM_EVENT_ERROR)
						.build());
				context.getSink().tryEmitComplete();
			}

			// 取消残留订阅并将上下文标记为已清理。
			context.cleanup();
		}
	}

	/**
	 * 处理流执行正常完成。
	 *
	 * 正常完成时除了发 `complete` 事件，还会通知多轮上下文管理器“本轮已完成”，
	 * 这样后续新问题才能正确拼接历史上下文。
	 */
	private void handleStreamComplete(String agentId, String threadId) {
		// 记录 Graph 已经正常发出完成信号。
		log.info("Stream processing completed successfully for threadId: {}", threadId);

		// 将本轮 pending 问题和已经累计的 Planner 输出正式写入多轮历史。
		multiTurnContextManager.finishTurn(threadId);

		// remove 同时完成“取得上下文”和“从活动任务表删除”，避免并发路径重复收尾。
		StreamContext context = streamContextMap.remove(threadId);

		// 上下文可能已经被取消或错误路径清理，因此必须同时检查存在性和清理标记。
		if (context != null && !context.isCleaned()) {
			// 正常完成 tracing，并把整条流累计的文本作为最终输出上报。
			if (context.getSpan() != null) {
				langfuseReporter.endSpanSuccess(context.getSpan(), threadId, context.getCollectedOutput());
			}

			// 只有前端仍在订阅时才发送业务 complete 事件，避免向无人消费的 sink 发数据。
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				// 先发送前端能识别的业务完成事件，其中包含 agentId 和 threadId。
				context.getSink()
					.tryEmitNext(ServerSentEvent.builder(GraphNodeResponse.complete(agentId, threadId))
						.event(STREAM_EVENT_COMPLETE)
						.build());

				// 再发送 Reactor 完成信号，通知 WebFlux 结束 SSE 响应。
				context.getSink().tryEmitComplete();
			}

			// 最后取消残留订阅并完成 sink；cleanup 内部使用 AtomicBoolean 保证只执行一次。
			context.cleanup();
		}
	}

	/**
	 * 分发单个 Graph 节点输出。
	 *
	 * 当前最重要的是 `StreamingOutput`，因为前端展示依赖的是增量文本。
	 * 如果未来引入新的 NodeOutput 类型，也应该优先在这里统一扩展。
	 */
	private void handleNodeOutput(GraphRequest request, NodeOutput output) {
		// 输出具体类型可帮助排查 Graph 是否产生了预期的流式节点输出。
		log.debug("Received output: {}", output.getClass().getSimpleName());

		// 当前前端协议只处理 StreamingOutput；其他 NodeOutput 类型在这里暂不转发。
		if (output instanceof StreamingOutput streamingOutput) {
			handleStreamNodeOutput(request, streamingOutput);
		}
	}

	/**
	 * 处理单个流式文本片段。
	 *
	 * 这里除了把 chunk 发给前端，还要额外做两件事：
	 * 1. 识别文本类型起止标记，区分普通文本和 JSON 块。
	 * 2. 对 Planner 节点输出做额外缓存，方便人工反馈时回看上一版计划。
	 */
	private void handleStreamNodeOutput(GraphRequest request, StreamingOutput output) {
		// 请求中的 threadId 用来找到对应的 SSE 运行现场。
		String threadId = request.getThreadId();
		StreamContext context = streamContextMap.get(threadId);

		// 上下文被停止或 sink 不存在时，忽略可能晚到的异步 chunk。
		if (context == null || context.getSink() == null) {
			log.debug("Stream processing already stopped for threadId: {}, skipping output", threadId);
			return;
		}

		// node 标识 chunk 来自哪个 Graph 节点，chunk 是本次增量文本。
		String node = output.node();
		String chunk = output.chunk();
		log.debug("Received Stream output: {}", chunk);

		// 空 chunk 没有展示价值，也不能参与类型状态机判断。
		if (chunk == null || chunk.isEmpty()) {
			return;
		}

		// originType 保存上一片段结束时所处的文本类型。
		TextType originType = context.getTextType();
		TextType textType;

		// 类型标记只控制状态切换，不应该作为正文发送。
		boolean isTypeSign = false;

		// 首个片段需要根据起始标记判断初始类型。
		if (originType == null) {
			textType = TextType.getTypeByStratSign(chunk);
			if (textType != TextType.TEXT) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}
		else {
			// 后续片段根据当前类型和 chunk 判断是否进入或退出结构化块。
			textType = TextType.getType(originType, chunk);
			if (textType != originType) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}

		// 文本类型标记只用于后端状态机，不应该泄露给前端。
		if (!isTypeSign) {
			// 累计全部可见文本，供 tracing 完成时作为输出上报。
			context.appendOutput(chunk);

			// Planner 的正文还要进入 pending turn，正常完成后形成下一轮的历史上下文。
			if (PlannerNode.class.getSimpleName().equals(node)) {
				multiTurnContextManager.appendPlannerChunk(threadId, chunk);
			}

			// 将 Graph 层输出转换为前端协议对象。
			GraphNodeResponse response = GraphNodeResponse.builder()
				.agentId(request.getAgentId())
				.threadId(threadId)
				.nodeName(node)
				.text(chunk)
				.textType(textType)
				.build();

			// `tryEmitNext` 不会阻塞，它会返回一个发射结果，让调用方自行决定失败策略。
			Sinks.EmitResult result = context.getSink().tryEmitNext(ServerSentEvent.builder(response).build());

			// 发射失败通常表示订阅已取消或 sink 已终止，继续运行会浪费资源。
			if (result.isFailure()) {
				log.warn("Failed to emit data to sink for threadId: {}, result: {}. Stopping stream processing.",
						threadId, result);
				stopStreamProcessing(threadId);
			}
		}
	}

}
