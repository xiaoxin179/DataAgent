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
package com.alibaba.cloud.ai.dataagent.enums;

import lombok.Getter;

/**
 * KnowledgeType：枚举定义。
 *
 * 它把知识Type相关的固定取值集中管理，避免状态值散落在各个业务类中硬编码。
 * 重点关注每个枚举值在参数校验、分支判断和外部协议中的含义。
 */
@Getter
public enum KnowledgeType {

	/**
	 * 文档类型
	 */
	DOCUMENT("DOCUMENT", "文档类型"),

	/**
	 * 问答类型
	 */
	QA("QA", "问答类型"),

	/**
	 * 常见问题类型
	 */
	FAQ("FAQ", "常见问题类型");

	private final String code;

	private final String description;

	KnowledgeType(String code, String description) {
		this.code = code;
		this.description = description;
	}

	/**
 * `fromCode`：执行当前类对外暴露的一步核心操作。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	public static KnowledgeType fromCode(String code) {
		for (KnowledgeType type : values()) {
			// 严格比对
			if (type.getCode().equals(code)) {
				return type;
			}
		}
		throw new IllegalArgumentException("未知的知识类型代码: " + code);
	}

}
