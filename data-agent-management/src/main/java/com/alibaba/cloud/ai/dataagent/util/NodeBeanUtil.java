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

import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 从 Spring 容器里取出 Graph 相关的节点或边 Bean，并在需要时包装成图引擎可直接调度的异步动作对象。
 *
 * <p>这里的核心思路是：
 * <ul>
 * <li>先通过 {@link ApplicationContext} 按类型拿到原始的 {@link NodeAction} / {@link EdgeAction} Bean</li>
 * <li>再把它们包装成 {@link AsyncNodeAction} / {@link AsyncEdgeAction}</li>
 * <li>这样 {@code StateGraph} 在注册节点和边时，拿到的就是可以异步执行的动作对象</li>
 * </ul>
 *
 * <p>注意：这里不是把 Spring 容器里的 bean 本体“改造成异步 bean”，而是生成一层异步适配器，
 * 让 Graph 这一层能够按自己的执行协议去调度它们。
 *
 * @author vlsmb
 * @since 2025/9/28
 */
@Component
@AllArgsConstructor
public class NodeBeanUtil {

	private final ApplicationContext context;

	/**
	 * 从 Spring 容器里按类型获取一个节点 Bean。
	 *
	 * <p>这个方法只负责“取出原始节点”，不做异步包装。
	 */
	public <T extends NodeAction> NodeAction getNodeBean(Class<T> clazz) {
		return context.getBean(clazz);
	}

	/**
	 * 从 Spring 容器里获取一个节点 Bean，并包装成异步节点对象。
	 *
	 * <p>调用方拿到的不是原始 Bean 本身的执行结果，而是一个可以被 StateGraph 异步调度的
	 * {@link AsyncNodeAction}。
	 */
	public <T extends NodeAction> AsyncNodeAction getNodeBeanAsync(Class<T> clazz) {
		return AsyncNodeAction.node_async(getNodeBean(clazz));
	}

	/**
	 * 从 Spring 容器里按类型获取一个边 Bean。
	 *
	 * <p>这个方法只负责“取出原始边”，不做异步包装。
	 */
	public <T extends EdgeAction> EdgeAction getEdgeBean(Class<T> clazz) {
		return context.getBean(clazz);
	}

	/**
	 * 从 Spring 容器里获取一个边 Bean，并包装成异步边对象。
	 *
	 * <p>这样 Graph 在执行条件跳转时，可以按异步边的形式去调度和处理分支决策。
	 */
	public <T extends EdgeAction> AsyncEdgeAction getEdgeBeanAsync(Class<T> clazz) {
		return AsyncEdgeAction.edge_async(getEdgeBean(clazz));
	}

}
