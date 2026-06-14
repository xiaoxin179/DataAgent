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

// 读取项目配置，例如最多保留多少轮历史、每个计划最多保存多少字符。
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
// 为所有 final 字段生成构造方法，Spring 会通过该构造方法注入配置对象。
import lombok.AllArgsConstructor;
// 为当前类生成名为 log 的日志对象。
import lombok.extern.slf4j.Slf4j;
// 提供字符串判空、去除空白、截断等常用方法。
import org.apache.commons.lang3.StringUtils;
// 将当前类注册为 Spring Bean，供 GraphServiceImpl 注入使用。
import org.springframework.stereotype.Component;

// ArrayDeque 用作双端队列，便于从头部删除旧轮次、从尾部追加新轮次。
import java.util.ArrayDeque;
// Deque 表示双端队列，这里按照时间顺序保存每个会话的历史轮次。
import java.util.Deque;
// Map 用来建立 threadId 与对应上下文数据之间的映射。
import java.util.Map;
// ConcurrentHashMap 保证多个请求线程访问不同会话时，Map 本身是线程安全的。
import java.util.concurrent.ConcurrentHashMap;
// Collectors 用来把多轮历史拼接成一段可以注入提示词的文本。
import java.util.stream.Collectors;

/**
 * 多轮对话上下文管理器。
 *
 * <p>
 * 它按照 threadId 保存两类数据：
 * </p>
 * <ul>
 * <li>history：已经正常完成的“用户问题 + AI 计划”；</li>
 * <li>pendingTurns：当前正在执行、尚未完成的用户问题和流式计划。</li>
 * </ul>
 *
 * <p>
 * GraphServiceImpl 会在一轮任务开始、流式输出、正常完成、取消以及人工反馈时调用本类，
 * 从而保证上一轮的计划能够成为下一轮提示词的一部分。
 * </p>
 */
// 生成日志字段 log，供计划为空等场景记录调试信息。
@Slf4j
// 把当前类交给 Spring 容器管理。
@Component
// 生成包含 properties 参数的构造方法，完成构造器注入。
@AllArgsConstructor
public class MultiTurnContextManager {

	// 保存项目级配置，用于限制历史轮数和单个计划的最大长度。
	private final DataAgentProperties properties;

	// 当前历史只保存在应用内存中，应用重启或多实例部署时无法共享，后续可改为数据库或缓存。
	// 外层 Map 的 key 是 threadId，value 是该会话已经完成的历史轮次队列。
	private final Map<String, Deque<ConversationTurn>> history = new ConcurrentHashMap<>();

	// 保存尚未完成的轮次；key 是 threadId，value 是当前问题和正在拼接的 Planner 输出。
	private final Map<String, PendingTurn> pendingTurns = new ConcurrentHashMap<>();

	/**
	 * 开始记录一轮新对话。
	 * @param threadId 会话唯一标识，用于区分不同用户或不同会话
	 * @param userQuestion 当前轮用户提出的问题
	 */
	public void beginTurn(String threadId, String userQuestion) {
		// threadId 或问题为空时无法建立有效轮次，因此直接忽略。
		if (StringUtils.isAnyBlank(threadId, userQuestion)) {
			return;
		}

		// 去除问题首尾空白，创建进行中轮次；相同 threadId 的旧进行中轮次会被覆盖。
		pendingTurns.put(threadId, new PendingTurn(userQuestion.trim()));
	}

	/**
	 * 追加当前轮 Planner 流式产生的一段文本。
	 * @param threadId 会话唯一标识
	 * @param chunk Planner 本次流式返回的文本片段
	 */
	public void appendPlannerChunk(String threadId, String chunk) {
		// 缺少会话标识或文本片段时，没有内容需要追加。
		if (StringUtils.isAnyBlank(threadId, chunk)) {
			return;
		}

		// 根据 threadId 找到 beginTurn 创建的进行中轮次。
		PendingTurn pending = pendingTurns.get(threadId);

		// 只有当前轮次仍然存在时才追加，任务取消后到达的迟到数据会被忽略。
		if (pending != null) {
			// Planner 的输出是分段到达的，因此使用 StringBuilder 按顺序拼成完整计划。
			pending.planBuilder.append(chunk);
		}
	}

	/**
	 * 正常结束当前轮，并把有效的“用户问题 + AI 计划”转移到历史记录。
	 * @param threadId 会话唯一标识
	 */
	public void finishTurn(String threadId) {
		// remove 同时完成“取出”和“删除”，避免同一轮被重复保存。
		PendingTurn pending = pendingTurns.remove(threadId);

		// 没有进行中的轮次，说明它可能已经完成、取消，或者从未开始。
		if (pending == null) {
			return;
		}

		// 把累计的 Planner 文本转成字符串，并去除首尾空白；null 会安全转换为空字符串。
		String plan = StringUtils.trimToEmpty(pending.planBuilder.toString());

		// 没有生成有效计划时不写入历史，防止空内容污染下一轮提示词。
		if (StringUtils.isBlank(plan)) {
			log.debug("No planner output recorded for thread {}, skipping history update", threadId);
			return;
		}

		// 按配置截断过长计划，避免多轮上下文无限消耗模型 Token。
		String trimmedPlan = StringUtils.abbreviate(plan, properties.getMaxplanlength());

		// 当前会话第一次写历史时创建队列；已经存在时直接返回原队列。
		Deque<ConversationTurn> deque = history.computeIfAbsent(threadId, key -> new ArrayDeque<>());

		// ArrayDeque 本身不是线程安全的，因此修改同一会话的队列前需要加锁。
		synchronized (deque) {
			// 达到最大历史轮数后，从队头开始删除最旧的轮次。
			while (deque.size() >= properties.getMaxturnhistory()) {
				deque.pollFirst();
			}

			// 把本轮问题和截断后的计划追加到队尾，队列始终保持从旧到新的顺序。
			deque.addLast(new ConversationTurn(pending.userQuestion, trimmedPlan));
		}
	}

	/**
	 * 丢弃尚未完成的当前轮，但不影响之前已经保存的历史。
	 *
	 * <p>
	 * 浏览器断开、请求取消或任务被主动终止时会调用该方法。
	 * </p>
	 * @param threadId 会话唯一标识
	 */
	public void discardPending(String threadId) {
		// 删除未完成轮次，后续迟到的 Planner chunk 将因为找不到 pending 而被忽略。
		pendingTurns.remove(threadId);
	}

	/**
	 * 撤回最近一次已完成轮次，并使用原问题重新开始规划。
	 *
	 * <p>
	 * 典型场景是用户拒绝上一版计划：旧计划不能继续污染上下文，但原问题仍需保留。
	 * </p>
	 * @param threadId 会话唯一标识
	 */
	public void restartLastTurn(String threadId) {
		// 获取该会话已经完成的历史轮次队列。
		Deque<ConversationTurn> deque = history.get(threadId);

		// 没有历史时不存在可撤回的轮次。
		if (deque == null || deque.isEmpty()) {
			return;
		}

		// 先声明变量，使它可以在同步代码块外继续使用。
		ConversationTurn lastTurn;

		// ArrayDeque 不是线程安全的，删除队尾元素时需要锁住当前会话队列。
		synchronized (deque) {
			// 队尾保存的是最新轮次，删除它相当于撤回上一版计划。
			lastTurn = deque.pollLast();
		}

		// 成功取到历史轮次后，使用原问题重新创建一个尚未完成的轮次。
		if (lastTurn != null) {
			pendingTurns.put(threadId, new PendingTurn(lastTurn.userQuestion()));
		}
	}

	/**
	 * 把当前会话历史整理成适合注入模型提示词的文本。
	 * @param threadId 会话唯一标识
	 * @return 格式化后的多轮历史；没有历史时返回“(无)”
	 */
	public String buildContext(String threadId) {
		// 找到当前会话对应的历史轮次队列。
		Deque<ConversationTurn> deque = history.get(threadId);

		// 新会话或历史为空时，明确告诉下游当前没有多轮上下文。
		if (deque == null || deque.isEmpty()) {
			return "(无)";
		}

		// 按队列中从旧到新的顺序读取每一轮历史。
		return deque.stream()
			// 将结构化轮次转换为“用户问题 + AI 计划”的提示词片段。
			.map(turn -> "用户: " + turn.userQuestion() + "\nAI计划: " + turn.plan())
			// 使用换行连接所有轮次，形成最终注入 Graph 状态的上下文字符串。
			.collect(Collectors.joining("\n"));
	}

	/**
	 * 已完成轮次的不可变数据结构。
	 * @param userQuestion 该轮用户问题
	 * @param plan 该轮 Planner 生成的完整计划
	 */
	private record ConversationTurn(String userQuestion, String plan) {
	}

	/**
	 * 尚未完成轮次的内部状态。
	 *
	 * <p>
	 * 用户问题创建后保持不变，计划则随着流式 chunk 到达而不断追加。
	 * </p>
	 */
	private static class PendingTurn {

		// 保存当前轮原始用户问题，完成或重启轮次时需要使用。
		private final String userQuestion;

		// 按到达顺序累计 Planner 的流式输出，最终形成完整计划。
		private final StringBuilder planBuilder = new StringBuilder();

		/**
		 * 创建一个尚未完成的新轮次。
		 * @param userQuestion 当前轮用户问题
		 */
		private PendingTurn(String userQuestion) {
			// 保存用户问题，等待 Planner 输出完成后与计划一起写入 history。
			this.userQuestion = userQuestion;
		}

	}

}
