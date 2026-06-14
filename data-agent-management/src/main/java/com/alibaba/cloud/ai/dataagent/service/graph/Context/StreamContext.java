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
package com.alibaba.cloud.ai.dataagent.service.graph.Context;

import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import io.opentelemetry.api.trace.Span;
import lombok.Data;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式处理上下文，封装每个 threadId 的所有相关状态
 *
 * @author Makoto
 * @since 2025/11/28
 */
@Data
public class StreamContext {

	// 保存 Graph 输出流的订阅句柄，浏览器断开时通过 dispose() 主动取消后台消费。
	private Disposable disposable;

	// 保存当前 HTTP 请求对应的 SSE 生产端，GraphServiceImpl 通过它向前端推送事件。
	private Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink;

	// OpenTelemetry/Langfuse 链路追踪对象，任务完成、异常或取消时必须结束。
	private Span span;

	// 记录当前流式文本处于普通文本还是 JSON 等结构化块中。
	private TextType textType;

	// 累计所有真正发送给前端的正文，供 tracing 在结束时记录完整输出。
	// final 只保证引用不被替换，StringBuilder 内部内容仍可以持续 append。
	private final StringBuilder outputCollector = new StringBuilder();

	/**
	 * 将一个非空流式片段追加到完整输出中。
	 * @param chunk Graph 节点本次产生的文本片段
	 */
	public void appendOutput(String chunk) {
		// 使用同一个 StringBuilder 按到达顺序拼接所有 chunk。
		outputCollector.append(chunk);
	}

	/**
	 * 获取当前已经累计的完整输出。
	 * @return 所有 chunk 拼接后的字符串
	 */
	public String getCollectedOutput() {
		// toString() 返回当前快照，不会把内部 StringBuilder 暴露给调用方。
		return outputCollector.toString();
	}

	// 一次性清理开关；compareAndSet 保证多个并发收尾路径中只有一个真正执行 cleanup。
	private final AtomicBoolean cleaned = new AtomicBoolean(false);

	/**
	 * 幂等地释放 Reactor 订阅和 SSE sink。
	 */
	public void cleanup() {
		// 只有第一个把 false 改成 true 的线程可以继续；其他线程直接返回。
		if (!cleaned.compareAndSet(false, true)) {
			return;
		}

		// 先复制字段引用，避免清理过程中字段被其他线程重新读取或修改。
		Disposable localDisposable = disposable;

		// 存在仍活动的订阅时，主动取消 Graph 输出消费。
		if (localDisposable != null && !localDisposable.isDisposed()) {
			try {
				localDisposable.dispose();
			}
			catch (Exception e) {
				// 清理属于兜底动作，取消失败不应阻止后续 sink 完成。
			}
		}

		// 同样复制 sink 引用，保证下面的检查和调用针对同一个对象。
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> localSink = sink;

		// 完成 sink，让仍在等待的下游收到 onComplete。
		if (localSink != null) {
			try {
				localSink.tryEmitComplete();
			}
			catch (Exception e) {
				// sink 可能已经终止；重复完成失败不再向外抛出。
			}
		}
	}

	/**
	 * 判断上下文是否已经进入清理状态。
	 */
	public boolean isCleaned() {
		// AtomicBoolean.get() 提供并发可见性，其他线程能及时看到 cleanup 的结果。
		return cleaned.get();
	}

}
