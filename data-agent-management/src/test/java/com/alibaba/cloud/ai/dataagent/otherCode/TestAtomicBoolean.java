package com.alibaba.cloud.ai.dataagent.otherCode;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestAtomicBoolean {

	// 这个测试用来直观看到 AtomicBoolean 的原子性
	@Test
	void shouldShowAtomicBooleanIsThreadSafe() throws Exception {
		// 线程数越多，并发竞争越明显
		int threadCount = 100;

		// 普通 boolean 的测试结果
		int plainSuccess = testPlainBoolean(threadCount);
		// AtomicBoolean 的测试结果
		int atomicSuccess = testAtomicBoolean(threadCount);

		// 打印普通 boolean 的结果
		System.out.println("普通 boolean 成功次数 = " + plainSuccess);
		// 打印 AtomicBoolean 的结果
		System.out.println("AtomicBoolean 成功次数 = " + atomicSuccess);

		assertEquals(threadCount, plainSuccess, "普通 boolean 的检查与修改不是原子操作");
		assertEquals(1, atomicSuccess, "compareAndSet(false, true) 只能成功一次");
	}

	// 演示普通 boolean 在并发下可能出现多个线程同时“成功”
	private int testPlainBoolean(int threadCount) throws Exception {
		// 创建固定大小线程池
		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		// 等待所有线程准备好，等待固定大小的线程都到达准备状态
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		// 统一放行所有线程
		CountDownLatch startLatch = new CountDownLatch(1);
		// 第一步：等待所有线程都完成“读取 false”
		CountDownLatch readLatch = new CountDownLatch(threadCount);
		// 第二步：等待主线程统一允许“写 true”
		CountDownLatch writeLatch = new CountDownLatch(1);
		// 等待所有线程结束
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		// 统计有多少线程认为自己成功了
		AtomicInteger successCount = new AtomicInteger(0);
		// 普通 boolean 标记，故意不加线程安全保护
		PlainFlag flag = new PlainFlag();

		// 启动多个线程同时抢这个标记
		for (int i = 0; i < threadCount; i++) {
			pool.submit(() -> {
				// 告诉主线程：我已经准备好了
				readyLatch.countDown();
				// 等待主线程统一放行
				await(startLatch);
				// 第一步先读取当前值
				boolean observed = flag.value;
				// 告诉主线程：我已经读完了
				readLatch.countDown();
				// 等待所有线程都完成读取，确保大家看到的是同一个旧值
				await(readLatch);
				// 等待主线程统一放行写入
				await(writeLatch);
				// 典型的“先判断，再修改”，不是原子操作
				if (!observed) {
					// 让出 CPU，放大并发竞争
					Thread.yield();
					// 修改标记
					flag.value = true;
					// 记录一次成功
					successCount.incrementAndGet();
				}
				// 当前线程执行结束
				doneLatch.countDown();
			});
		}

		// 等待所有线程都准备好
		readyLatch.await();
		// 同时开始执行
		startLatch.countDown();
		// 等待所有线程都完成读取
		readLatch.await();
		// 统一放行写入阶段
		writeLatch.countDown();
		// 等待所有线程结束
		doneLatch.await(3, TimeUnit.SECONDS);
		// 关闭线程池
		pool.shutdownNow();
		// 返回成功次数
		return successCount.get();
	}

	// 演示 AtomicBoolean 的 compareAndSet 只能成功一次
	private int testAtomicBoolean(int threadCount) throws Exception {
		// 创建固定大小线程池
		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		// 等待所有线程准备好
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		// 统一放行所有线程
		CountDownLatch startLatch = new CountDownLatch(1);
		// 等待所有线程结束
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		// 统计成功次数
		AtomicInteger successCount = new AtomicInteger(0);
		// 原子布尔值
		AtomicFlag flag = new AtomicFlag();

		// 启动多个线程同时抢这个标记
		for (int i = 0; i < threadCount; i++) {
			pool.submit(() -> {
				// 告诉主线程：我已经准备好了
				readyLatch.countDown();
				// 等待主线程统一放行
				await(startLatch);
				// compareAndSet(false, true) 是原子操作，只会有一个线程成功
				if (flag.value.compareAndSet(false, true)) {
					// 记录一次成功
					successCount.incrementAndGet();
				}
				// 当前线程执行结束
				doneLatch.countDown();
			});
		}

		// 等待所有线程都准备好
		readyLatch.await();
		// 同时开始执行
		startLatch.countDown();
		// 等待所有线程结束
		doneLatch.await(3, TimeUnit.SECONDS);
		// 关闭线程池
		pool.shutdownNow();
		// 返回成功次数
		return successCount.get();
	}

	// 统一处理 CountDownLatch 等待
	private void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// 普通 boolean 包装类
	private static class PlainFlag {
		// 标记值
		private boolean value = false;
	}

	// AtomicBoolean 包装类
	private static class AtomicFlag {
		// 原子布尔值
		private final AtomicBoolean value = new AtomicBoolean(false);
	}
}
