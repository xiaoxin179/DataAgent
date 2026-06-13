package com.alibaba.cloud.ai.dataagent.otherCode;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Locale;
import java.util.Scanner;

public class TestSink {

	record OrderEvent(String orderId, String status, String message) {
	}

	public static void main(String[] args) {
		new TestSink().runInteractiveDemo();
	}

	void runInteractiveDemo() {
		Sinks.Many<OrderEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

		Flux<OrderEvent> orderFlux = sink.asFlux()
			.doOnSubscribe(subscription -> System.out.println("[生命周期] 订阅者开始订阅事件流"))
			.doOnNext(event -> System.out.println("[进入 Flux] " + event))
			.filter(event -> {
				boolean passed = event.message() != null && !event.message().isBlank();
				System.out.println(passed ? "[filter] 通过" : "[filter] 拦截：message 为空");
				return passed;
			})
			.doOnNext(event -> System.out.println("[通过 filter] " + event))
			.doOnDiscard(OrderEvent.class, event -> System.out.println("[被丢弃] " + event))
			.doOnCancel(() -> System.out.println("[生命周期] 订阅被取消"))
			.doOnError(error -> System.out.println("[生命周期] 事件流出现异常：" + error.getMessage()))
			.doOnComplete(() -> System.out.println("[生命周期] 事件流正常完成"));

		Disposable disposable = orderFlux.subscribe(event -> System.out.println("[订阅者收到] " + event),
				error -> System.out.println("[订阅者错误回调] " + error.getMessage()),
				() -> System.out.println("[订阅者完成回调]"));

		try (Scanner scanner = new Scanner(System.in)) {
			while (true) {
				printMenu();
				String action = scanner.nextLine().trim().toLowerCase(Locale.ROOT);

				switch (action) {
					case "send" -> emitNext(sink, readEvent(scanner));
					case "filter" -> {
						OrderEvent emptyMessageEvent = new OrderEvent("FILTER-DEMO", "CREATED", "");
						System.out.println("[准备发送空消息事件] " + emptyMessageEvent);
						emitNext(sink, emptyMessageEvent);
					}
					case "complete" -> {
						System.out.println("[生产者] 发送完成信号");
						System.out.println("[发送结果] " + sink.tryEmitComplete());
						return;
					}
					case "error" -> {
						System.out.println("[生产者] 发送错误信号");
						System.out.println("[发送结果] "
								+ sink.tryEmitError(new RuntimeException("手动触发的测试异常")));
						return;
					}
					case "cancel" -> {
						System.out.println("[订阅者] 主动取消订阅");
						disposable.dispose();
						return;
					}
					case "exit" -> {
						System.out.println("[程序] 退出前取消订阅");
						disposable.dispose();
						return;
					}
					default -> System.out.println("未知操作，请重新输入。");
				}
			}
		}
	}

	private void emitNext(Sinks.Many<OrderEvent> sink, OrderEvent event) {
		System.out.println("[生产者发送] " + event);
		Sinks.EmitResult result = sink.tryEmitNext(event);
		System.out.println("[发送结果] " + result);
	}

	private void printMenu() {
		System.out.println();
		System.out.println("send     发送自定义事件（message 要填写内容）");
		System.out.println("filter   自动发送空 message，观察 filter 拦截");
		System.out.println("complete 发送正常完成信号并结束");
		System.out.println("error    发送错误信号并结束");
		System.out.println("cancel   取消订阅并结束");
		System.out.println("exit     退出程序");
		System.out.print("> ");
	}

	private OrderEvent readEvent(Scanner scanner) {
		System.out.print("请输入 orderId: ");
		String orderId = scanner.nextLine();
		System.out.print("请输入 status: ");
		String status = scanner.nextLine();
		System.out.print("请输入 message（例如：订单已支付）: ");
		String message = scanner.nextLine();
		return new OrderEvent(orderId, status, message);
	}

}
