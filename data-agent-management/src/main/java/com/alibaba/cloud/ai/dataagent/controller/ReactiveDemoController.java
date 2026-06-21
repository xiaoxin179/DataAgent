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
package com.alibaba.cloud.ai.dataagent.controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * WebFlux 学习用测试接口。
 *
 * 这个 Controller 不参与业务流程，只用来对比：
 * 1. 阻塞写法：线程真的停在 Thread.sleep 上。
 * 2. 响应式写法：先返回 Mono，等 10 秒后再由 Reactor 推送结果。
 */
@Slf4j
@RestController
@RequestMapping("/api/reactive-demo")
public class ReactiveDemoController {

	/**
	 * 阻塞接口：当前处理请求的线程会被 Thread.sleep 卡住 10 秒。
	 */
	@GetMapping("/blocking")
	public String blocking() throws InterruptedException {
		String threadName = Thread.currentThread().getName();
		LocalDateTime start = LocalDateTime.now();

		Thread.sleep(10_000);

		return """
				blocking finished
				start: %s
				end: %s
				thread: %s
				meaning: 这个接口用 Thread.sleep 阻塞了当前线程 10 秒
				""".formatted(start, LocalDateTime.now(), threadName);
	}

	/**
	 * 非阻塞接口：这里没有 Thread.sleep，而是告诉 Reactor “10 秒后再给结果”。
	 */
	@GetMapping("/non-blocking")
	public Mono<String> nonBlocking() {
		String threadName = Thread.currentThread().getName();
		LocalDateTime start = LocalDateTime.now();

		return Mono.delay(Duration.ofSeconds(10)).map(ignored -> """
				non-blocking finished
				start: %s
				end: %s
				created-thread: %s
				callback-thread: %s
				meaning: 这个接口没有阻塞当前线程，而是 10 秒后响应式地推送结果
				""".formatted(start, LocalDateTime.now(), threadName, Thread.currentThread().getName()));
	}

	/**
	 * 立刻返回接口：用来在等待上面两个慢接口时，验证服务是否还能处理新请求。
	 */
	@GetMapping("/ping")
	public Mono<String> ping() {
		return Mono.just("pong " + LocalDateTime.now() + " thread=" + Thread.currentThread().getName());
	}

	/**
	 * 定时流接口：每隔 1 秒向前端推送一个数字。
	 *
	 * produces = TEXT_EVENT_STREAM_VALUE 表示这里返回的是 SSE 流，不是一次性普通响应。
	 */
	@GetMapping(value = "/interval", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<Long> interval() {
		return Flux.interval(Duration.ofSeconds(1))
			.doOnSubscribe(subscription -> log.info("前端开始订阅 Flux.interval 定时流"))
			.doOnNext(number -> log.info("后端向前端发送定时流数字：{}", number))
			.doOnCancel(() -> log.info("前端停止接收，后端收到 cancel 信号，Flux.interval 订阅被取消"))
			.doOnComplete(() -> log.info("Flux.interval 定时流正常完成"))
			.doOnError(error -> log.error("Flux.interval 定时流发生异常", error));
	}

	/**
	 * 手动构造一个 SSE 事件。
	 *
	 * event/id/data 会分别变成浏览器 EventSource 收到的事件名、事件 ID 和正文数据。
	 */
	@GetMapping(value = "/sse-message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> sseMessage() {
		return Flux.just(ServerSentEvent.<String>builder().event("message").id("1").data("hello").build());
	}

	/**
	 * 无缓存慢消费者演示。
	 *
	 * 生产者会瞬间发送 30 条数据，消费者每 500ms 才处理 1 条。
	 * OverflowStrategy.ERROR 表示没有缓存兜底，下游来不及要数据时会直接报 overflow。
	 */
	@GetMapping(value = "/slow-consumer/no-buffer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> slowConsumerNoBuffer() {
		AtomicInteger producedCount = new AtomicInteger();
		AtomicInteger consumedCount = new AtomicInteger();

		Flux<Integer> fastProducer = Flux.create(sink -> {
			for (int i = 1; i <= 30; i++) {
				producedCount.incrementAndGet();
				log.info("无缓存演示：生产者快速发送第 {} 条", i);
				sink.next(i);
			}
			sink.complete();
		}, FluxSink.OverflowStrategy.ERROR);

		return buildSlowConsumerResponse("no-buffer", fastProducer, producedCount, consumedCount);
	}

	/**
	 * 有缓存慢消费者演示。
	 *
	 * unicast().onBackpressureBuffer() 会先把来不及消费的数据放进内部缓冲区。
	 * 所以生产者可以快速发送，消费者仍然能慢慢收到完整数据。
	 */
	@GetMapping(value = "/slow-consumer/buffer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> slowConsumerBuffer() {
		AtomicInteger producedCount = new AtomicInteger();
		AtomicInteger consumedCount = new AtomicInteger();
		Sinks.Many<Integer> sink = Sinks.many().unicast().onBackpressureBuffer();

		Mono.fromRunnable(() -> {
			for (int i = 1; i <= 30; i++) {
				producedCount.incrementAndGet();
				log.info("有缓存演示：生产者快速发送第 {} 条", i);
				Sinks.EmitResult result = sink.tryEmitNext(i);
				if (result.isFailure()) {
					log.warn("有缓存演示：第 {} 条发送失败，原因：{}", i, result);
				}
			}
			sink.tryEmitComplete();
		}).subscribeOn(Schedulers.boundedElastic()).subscribe();

		return buildSlowConsumerResponse("buffer", sink.asFlux(), producedCount, consumedCount);
	}

	private Flux<ServerSentEvent<String>> buildSlowConsumerResponse(String mode, Flux<Integer> source,
			AtomicInteger producedCount, AtomicInteger consumedCount) {
		return source
			.concatMap(number -> Mono.delay(Duration.ofMillis(500)).thenReturn(number), 1)
			.map(number -> {
				int consumed = consumedCount.incrementAndGet();
				log.info("{} 演示：慢消费者处理第 {} 条，当前已消费 {} 条", mode, number, consumed);
				return ServerSentEvent.<String>builder()
					.event("data")
					.id(String.valueOf(number))
					.data("mode=" + mode + ", received=" + number + ", produced=" + producedCount.get()
							+ ", consumed=" + consumed)
					.build();
			})
			.concatWith(Mono.fromSupplier(() -> ServerSentEvent.<String>builder()
				.event("summary")
				.data("mode=" + mode + ", complete, produced=" + producedCount.get() + ", consumed="
						+ consumedCount.get())
				.build()))
			.onErrorResume(error -> Flux.just(ServerSentEvent.<String>builder()
				.event("overflow")
				.data("mode=" + mode + ", overflow, produced=" + producedCount.get() + ", consumed="
						+ consumedCount.get() + ", error=" + error.getClass().getSimpleName() + ": "
						+ error.getMessage())
				.build()));
	}

}
