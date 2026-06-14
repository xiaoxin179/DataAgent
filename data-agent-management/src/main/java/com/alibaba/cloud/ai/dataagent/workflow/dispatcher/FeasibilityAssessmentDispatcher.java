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

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.FEASIBILITY_ASSESSMENT_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 可行性评估分发器。
 *
 * 这里依赖的是一份“文本协议”：
 * - `FeasibilityAssessmentNode` 输出一段结构化评估文本。
 * - 文本里会包含类似 “【需求类型】：《数据分析》” 的字段。
 * - Dispatcher 只做字符串判断，不再次调用模型。
 *
 * 这种做法的好处是轻量，坏处是对提示词格式有依赖。
 * 如果评估模板变了，这里必须同步修改。
 */
@Slf4j
public class FeasibilityAssessmentDispatcher implements EdgeAction {

	/**
	 * 按可行性评估文本协议判断是否进入规划阶段。
	 */
	@Override
	public String apply(OverAllState state) throws Exception {
		// 这里的 value 与 `resources/feasibility-assessment.txt` 约定的输出格式保持一致。
		// 典型内容例如：
		// 【需求类型】：《数据分析》
		// 【语言类型】：《中文》
		// 【需求内容】：查询所有“核心用户”的数量
		String value = state.value(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, END);

		// 只有明确识别为数据分析的需求才值得继续生成执行计划。
		if (value != null && value.contains("【需求类型】：《数据分析》")) {
			log.info("[FeasibilityAssessmentNodeDispatcher] 需求类型为数据分析，进入 PlannerNode。");
			return PLANNER_NODE;
		}
		else {
			// 闲聊、信息不足或非数据分析需求在这里结束。
			log.info("[FeasibilityAssessmentNodeDispatcher] 需求类型非数据分析，返回 END。");
			return END;
		}
	}

}
