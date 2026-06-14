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
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.service.langfuse.LangfuseService;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

/**
 * @author vlsmb
 * @since 2025/10/22
 */
public final class FluxUtil {

	// 工具类不保存实例状态，禁止外部创建对象。
	private FluxUtil() {
	}

	/**
	 * 级联两个具有前后关系的Flux
	 * @param originFlux 第一个Flux
	 * @param nextFluxFunc 根据第一个Flux的聚合结果生成第二个Flux
	 * @param aggregator 聚合第一个Flux的所有数据
	 * @param preFlux 在第一个Flux前添加的信息Flux
	 * @param middleFlux 在第一个Flux和第二个Flux之间添加的信息Flux
	 * @param endFlux 在第二个Flux后添加的信息Flux
	 */
	public static <T, R> Flux<T> cascadeFlux(Flux<T> originFlux, Function<R, Flux<T>> nextFluxFunc,
			Function<Flux<T>, Mono<R>> aggregator, Flux<T> preFlux, Flux<T> middleFlux, Flux<T> endFlux) {
		// 缓存原始流，展示订阅和聚合订阅共享同一次上游执行。
		Flux<T> cachedOrigin = originFlux.cache();

		// 把第一段流聚合成 R，并缓存结果供第二段流工厂使用。
		Mono<R> aggregatedResult = aggregator.apply(cachedOrigin).cache();

		// 第一段全部完成后，使用聚合结果创建依赖它的第二段流。
		Flux<T> secondFlux = aggregatedResult.flatMapMany(nextFluxFunc);

		// concatWith 保证各段严格按前后顺序执行，不发生并行交错。
		return preFlux.concatWith(cachedOrigin).concatWith(middleFlux).concatWith(secondFlux).concatWith(endFlux);
	}

	/**
	 * 级联两个具有前后关系的Flux
	 * @param originFlux 第一个Flux
	 * @param nextFluxFunc 根据第一个Flux的聚合结果生成第二个Flux
	 * @param aggregator 聚合第一个Flux的所有数据
	 */
	public static <T, R> Flux<T> cascadeFlux(Flux<T> originFlux, Function<R, Flux<T>> nextFluxFunc,
			Function<Flux<T>, Mono<R>> aggregator) {
		// 简化重载不插入额外展示流。
		return cascadeFlux(originFlux, nextFluxFunc, aggregator, Flux.empty(), Flux.empty(), Flux.empty());
	}

	/**
	 * Quickly create streaming generator with start and end messages
	 * @param nodeClass node class
	 * @param state state
	 * @param startMessage start message
	 * @param completionMessage completion message
	 * @param resultMapper result mapping function
	 * @param sourceFlux source data stream
	 * @return Flux instance
	 */
	public static Flux<GraphResponse<StreamingOutput>> createStreamingGeneratorWithMessages(
			Class<? extends NodeAction> nodeClass, OverAllState state, String startMessage, String completionMessage,
			Function<String, Map<String, Object>> resultMapper, Flux<ChatResponse> sourceFlux) {
		// 使用类名作为 Graph 输出中的节点标识。
		String nodeName = nodeClass.getSimpleName();

		// 只累计真实 sourceFlux 文本；开始和完成提示不应污染业务结果。
		final StringBuilder collectedResult = new StringBuilder();

		// startMessage 为空时不额外创建开始消息。
		Flux<ChatResponse> startFlux = (startMessage == null ? Flux.empty()
				: Flux.just(ChatResponseUtil.createResponse(startMessage)));

		// 展示开始消息，并在每个模型响应到达时同步累计正文。
		Flux<ChatResponse> wrapperFlux = startFlux.concatWith(sourceFlux.doOnNext(chatResponse -> {
			String text = ChatResponseUtil.getText(chatResponse);
			collectedResult.append(text);
		}));

		// 可选地在源流结束后追加完成提示。
		if (completionMessage != null) {
			wrapperFlux = wrapperFlux.concatWith(Flux.just(ChatResponseUtil.createResponse(completionMessage)));
		}

		// resultMapper 延迟到整条流完成时执行，把完整正文转换为 state 更新。
		return toStreamingResponseFlux(nodeName, state, wrapperFlux,
				() -> resultMapper.apply(collectedResult.toString()));
	}

	/**
	 * 不需要开始/完成提示时使用的简化重载。
	 */
	public static Flux<GraphResponse<StreamingOutput>> createStreamingGeneratorWithMessages(
			Class<? extends NodeAction> nodeClass, OverAllState state,
			Function<String, Map<String, Object>> resultMapper, Flux<ChatResponse> sourceFlux) {
		return createStreamingGeneratorWithMessages(nodeClass, state, null, null, resultMapper, sourceFlux);
	}

	/**
	 * create streaming generator with start and end flux
	 * @param nodeClass node class
	 * @param state state
	 * @param sourceFlux source data stream
	 * @param preFlux preFlux
	 * @param sufFlux sufFlux
	 * @param sourceMapper result of <code>sourceFlux</code> mapping function
	 * @return Flux instance
	 */
	public static Flux<GraphResponse<StreamingOutput>> createStreamingGenerator(Class<? extends NodeAction> nodeClass,
			OverAllState state, Flux<ChatResponse> sourceFlux, Flux<ChatResponse> preFlux, Flux<ChatResponse> sufFlux,
			Function<String, Map<String, Object>> sourceMapper) {
		// 使用节点类名标识输出来源。
		String nodeName = nodeClass.getSimpleName();

		// 只累计 sourceFlux，preFlux/sufFlux 的界面提示不会进入最终业务文本。
		final StringBuilder collectedResult = new StringBuilder();

		// doOnNext 不改变元素，只在旁路累计每个模型响应的文本。
		sourceFlux = sourceFlux.doOnNext(r -> collectedResult.append(ChatResponseUtil.getText(r)));

		// Flux.concat 保证前置消息、模型正文和后置消息依次输出。
		return toStreamingResponseFlux(nodeName, state, Flux.concat(preFlux, sourceFlux, sufFlux),
				() -> sourceMapper.apply(collectedResult.toString()));
	}

	private static Flux<GraphResponse<StreamingOutput>> toStreamingResponseFlux(String nodeName, OverAllState state,
			Flux<ChatResponse> sourceFlux, Supplier<Map<String, Object>> resultSupplier) {
		// tracing threadId 由 GraphServiceImpl 在初始 state 中写入。
		Object threadId = state.value(TRACE_THREAD_ID).orElse(null);

		// 将每个有效 ChatResponse 转成带节点身份和当前 state 的 StreamingOutput。
		Flux<GraphResponse<StreamingOutput>> streamingFlux = sourceFlux
			// 使用响应 metadata 累加 prompt/completion token。
			.doOnNext(response -> extractAndAccumulateTokens(threadId, response))
			// 过滤缺失 result/output 的空响应，避免下游空指针。
			.filter(response -> response != null && response.getResult() != null
					&& response.getResult().getOutput() != null)
			// GraphResponse.of 表示一个普通流式节点输出。
			.map(response -> GraphResponse.of(new StreamingOutput<>(response.getResult().getOutput(), response,
					nodeName, "", state, OutputType.from(true, nodeName))));

		// 所有 chunk 完成后追加 done 响应，Graph 框架会把其中 Map 合并进 state。
		return streamingFlux.concatWith(Mono.fromSupplier(() -> GraphResponse.done(resultSupplier.get())))
			// 将异常转换为 GraphResponse.error，统一交给图输出链处理。
			.onErrorResume(error -> Flux.just(GraphResponse.error(error)));
	}

	/**
	 * 从模型响应 metadata 中提取 token 使用量并累计到当前 tracing thread。
	 */
	private static void extractAndAccumulateTokens(Object threadId, ChatResponse response) {
		// 没有 threadId 或 metadata 时无法归属 token，直接忽略。
		if (threadId == null || response.getMetadata() == null) {
			return;
		}

		// Spring AI 将本次调用 token 统计封装在 Usage 中。
		Usage usage = response.getMetadata().getUsage();

		// 只累计模型真正返回的非零使用量。
		if (usage != null && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0)) {
			LangfuseService.accumulateTokens(threadId, usage.getPromptTokens(), usage.getCompletionTokens());
		}
	}

}
