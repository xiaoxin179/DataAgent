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
package com.alibaba.cloud.ai.dataagent.entity;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AgentPresetQuestion：持久化实体对象。
 *
 * 这个类通常和数据库里的智能体PresetQuestion记录对应，字段设计会直接影响 Mapper SQL 和状态保存方式。
 * 学习时建议重点看状态字段、时间字段，以及哪些字段会被接口层脱敏或忽略。
 */
@Data
@NoArgsConstructor
public class AgentPresetQuestion {

	private Long id;

	private Long agentId;

	private String question;

	private Integer sortOrder;

	private Boolean isActive;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

	public AgentPresetQuestion(Long agentId, String question, Integer sortOrder) {
		this.agentId = agentId;
		this.question = question;
		this.sortOrder = sortOrder;
		this.isActive = false;
	}

}
