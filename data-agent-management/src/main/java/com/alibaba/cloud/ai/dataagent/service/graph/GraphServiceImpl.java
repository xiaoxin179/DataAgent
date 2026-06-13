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
		OverAllState state = compiledGraph
			.invoke(Map.of(IS_ONLY_NL2SQL, true, INPUT_KEY, naturalQuery, AGENT_ID, agentId),
					RunnableConfig.builder().build())
			.orElseThrow();
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
		if (!StringUtils.hasText(graphRequest.getThreadId())) {
			graphRequest.setThreadId(UUID.randomUUID().toString());
		}
		String threadId = graphRequest.getThreadId();

		// 一个 threadId 只能对应一个活跃的流上下文；重复请求会复用同一个上下文对象。
		StreamContext context = streamContextMap.computeIfAbsent(threadId, k -> new StreamContext());
		context.setSink(sink);
		if (StringUtils.hasText(graphRequest.getHumanFeedbackContent())) {
			handleHumanFeedback(graphRequest);
		}
		else {
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
		if (!StringUtils.hasText(threadId)) {
			return;
		}
		log.info("Stopping stream processing for threadId: {}", threadId);
		multiTurnContextManager.discardPending(threadId);
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
		String query = graphRequest.getQuery();
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();
		boolean nl2sqlOnly = graphRequest.isNl2sqlOnly();
		boolean humanReviewEnabled = graphRequest.isHumanFeedback() & !(nl2sqlOnly);
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(query)) {
			throw new IllegalArgumentException("Invalid arguments");
		}

		StreamContext context = streamContextMap.get(threadId);
		if (context == null || context.getSink() == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}

		Span span = langfuseReporter.startLLMSpan("graph-stream", graphRequest);
		context.setSpan(span);

		String multiTurnContext = multiTurnContextManager.buildContext(threadId);
		multiTurnContextManager.beginTurn(threadId, query);
		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(
				Map.of(IS_ONLY_NL2SQL, nl2sqlOnly, INPUT_KEY, query, AGENT_ID, agentId, HUMAN_REVIEW_ENABLED,
						humanReviewEnabled, MULTI_TURN_CONTEXT, multiTurnContext, TRACE_THREAD_ID, threadId),
				RunnableConfig.builder().threadId(threadId).build());
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
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();
		String feedbackContent = graphRequest.getHumanFeedbackContent();
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(feedbackContent)) {
			throw new IllegalArgumentException("Invalid arguments");
		}

		StreamContext context = streamContextMap.get(threadId);
		if (context == null || context.getSink() == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}

		Span span = langfuseReporter.startLLMSpan("graph-feedback", graphRequest);
		context.setSpan(span);

		Map<String, Object> feedbackData = Map.of("feedback", !graphRequest.isRejectedPlan(), "feedback_content",
				feedbackContent);
		if (graphRequest.isRejectedPlan()) {
			// 用户拒绝上一版计划时，回滚多轮上下文到本轮开始前，避免错误计划文本继续污染上下文。
			multiTurnContextManager.restartLastTurn(threadId);
		}
		Map<String, Object> stateUpdate = new HashMap<>();
		stateUpdate.put(HUMAN_FEEDBACK_DATA, feedbackData);
		stateUpdate.put(MULTI_TURN_CONTEXT, multiTurnContextManager.buildContext(threadId));

		RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();
		RunnableConfig updatedConfig;
		try {
			// `updateState` 是图框架的“恢复点修改”能力，常用于 human-in-the-loop 场景。
			updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to update graph state for human feedback", e);
		}
		RunnableConfig resumeConfig = RunnableConfig.builder(updatedConfig)
			.addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackData)
			.build();

		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
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
		CompletableFuture.runAsync(() -> {
			if (context.isCleaned()) {
				log.debug("StreamContext cleaned before subscription for threadId: {}", threadId);
				return;
			}
			Disposable disposable = nodeOutputFlux.subscribe(output -> handleNodeOutput(graphRequest, output),
					error -> handleStreamError(agentId, threadId, error),
					() -> handleStreamComplete(agentId, threadId));

			// 订阅建立和上下文清理可能并发发生，因此这里做原子保护。
			synchronized (context) {
				if (context.isCleaned()) {
					if (disposable != null && !disposable.isDisposed()) {
						disposable.dispose();
					}
				}
				else {
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
		log.error("Error in stream processing for threadId: {}: ", threadId, error);
		StreamContext context = streamContextMap.remove(threadId);
		if (context != null && !context.isCleaned()) {
			if (context.getSpan() != null) {
				langfuseReporter.endSpanError(context.getSpan(), threadId,
						error instanceof Exception ? (Exception) error : new RuntimeException(error));
			}
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				context.getSink()
					.tryEmitNext(ServerSentEvent
						.builder(GraphNodeResponse.error(agentId, threadId,
								"Error in stream processing: " + error.getMessage()))
						.event(STREAM_EVENT_ERROR)
						.build());
				context.getSink().tryEmitComplete();
			}
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
		log.info("Stream processing completed successfully for threadId: {}", threadId);
		multiTurnContextManager.finishTurn(threadId);
		StreamContext context = streamContextMap.remove(threadId);
		if (context != null && !context.isCleaned()) {
			if (context.getSpan() != null) {
				langfuseReporter.endSpanSuccess(context.getSpan(), threadId, context.getCollectedOutput());
			}
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				context.getSink()
					.tryEmitNext(ServerSentEvent.builder(GraphNodeResponse.complete(agentId, threadId))
						.event(STREAM_EVENT_COMPLETE)
						.build());
				context.getSink().tryEmitComplete();
			}
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
		log.debug("Received output: {}", output.getClass().getSimpleName());
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
		String threadId = request.getThreadId();
		StreamContext context = streamContextMap.get(threadId);
		if (context == null || context.getSink() == null) {
			log.debug("Stream processing already stopped for threadId: {}, skipping output", threadId);
			return;
		}
		String node = output.node();
		String chunk = output.chunk();
		log.debug("Received Stream output: {}", chunk);

		if (chunk == null || chunk.isEmpty()) {
			return;
		}

		TextType originType = context.getTextType();
		TextType textType;
		boolean isTypeSign = false;
		if (originType == null) {
			textType = TextType.getTypeByStratSign(chunk);
			if (textType != TextType.TEXT) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}
		else {
			textType = TextType.getType(originType, chunk);
			if (textType != originType) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}

		// 文本类型标记只用于后端状态机，不应该泄露给前端。
		if (!isTypeSign) {
			context.appendOutput(chunk);
			if (PlannerNode.class.getSimpleName().equals(node)) {
				multiTurnContextManager.appendPlannerChunk(threadId, chunk);
			}
			GraphNodeResponse response = GraphNodeResponse.builder()
				.agentId(request.getAgentId())
				.threadId(threadId)
				.nodeName(node)
				.text(chunk)
				.textType(textType)
				.build();

			// `tryEmitNext` 不会阻塞，它会返回一个发射结果，让调用方自行决定失败策略。
			Sinks.EmitResult result = context.getSink().tryEmitNext(ServerSentEvent.builder(response).build());
			if (result.isFailure()) {
				log.warn("Failed to emit data to sink for threadId: {}, result: {}. Stopping stream processing.",
						threadId, result);
				stopStreamProcessing(threadId);
			}
		}
	}

}
