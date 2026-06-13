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
 * ModelType：枚举定义。
 *
 * 它把模型Type相关的固定取值集中管理，避免状态值散落在各个业务类中硬编码。
 * 重点关注每个枚举值在参数校验、分支判断和外部协议中的含义。
 */
@Getter
public enum ModelType {

	/**
	 * 对话模型
	 */
	CHAT("CHAT"),

	/**
	 * 嵌入模型
	 */
	EMBEDDING("EMBEDDING");

	private final String code;

	ModelType(String code) {
		this.code = code;
	}

	/**
 * `fromCode`：执行当前类对外暴露的一步核心操作。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	public static ModelType fromCode(String code) {
		for (ModelType type : values()) {
			// 严格比对
			if (type.getCode().equals(code)) {
				return type;
			}
		}
		throw new IllegalArgumentException("未知的模型类型代码: " + code);
	}

}
