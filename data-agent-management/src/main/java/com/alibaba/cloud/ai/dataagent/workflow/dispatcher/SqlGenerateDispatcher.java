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
package com.alibaba.cloud.ai.dataagent.workflow.dispatcher;

import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.SEMANTIC_CONSISTENCY_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * SQL 生成分发器。
 *
 * 这个 Dispatcher 负责消费 `SqlGenerateNode` 的产出，并决定：
 * - 是否进入语义一致性校验。
 * - 是否因为生成失败而重试。
 * - 是否已经触达最大重试次数，从而终止流程。
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlGenerateDispatcher implements EdgeAction {

	private final DataAgentProperties properties;

	/**
	 * 读取 SQL 生成结果和重试次数，返回下一节点名称。
	 */
	@Override
	public String apply(OverAllState state) {
		// SQL_GENERATE_OUTPUT 可能不存在，表示生成节点没有得到可用 SQL。
		Optional<Object> optional = state.value(SQL_GENERATE_OUTPUT);
		if (optional.isEmpty()) {
			// 读取当前生成次数；没有值时使用最大次数作为保守默认值，避免无限重试。
			int currentCount = state.value(SQL_GENERATE_COUNT, properties.getMaxSqlRetryCount());

			// 当前节点没有生成出 SQL 结果时，按配置的最大重试次数进行补救。
			if (currentCount < properties.getMaxSqlRetryCount()) {
				log.info("SQL 生成失败，开始重试，当前次数: {}", currentCount);
				return SQL_GENERATE_NODE;
			}
			log.error("SQL 生成失败，达到最大重试次数，结束流程。");
			return END;
		}

		// 节点约定该状态最终写入 SQL 字符串或特殊 END 标记。
		String sqlGenerateOutput = (String) optional.get();
		log.info("SQL 生成结果: {}", sqlGenerateOutput);

		// 生成节点主动返回 END 时，分发器直接终止流程。
		if (END.equals(sqlGenerateOutput)) {
			log.info("检测到流程终止标志: {}", END);
			return END;
		}
		else {
			// 普通 SQL 不能立即执行，要先验证它是否满足用户语义。
			log.info("SQL 生成成功，进入语义一致性检查节点: {}", SEMANTIC_CONSISTENCY_NODE);
			return SEMANTIC_CONSISTENCY_NODE;
		}
	}

}
