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
package com.alibaba.cloud.ai.dataagent.bo.schema;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DisplayStyleBO：服务内部流转的业务对象。
 *
 * 它通常承载DisplayStyle在处理中间阶段的结构化结果，方便不同服务方法之间传递。
 * 这类类适合配合调用链一起看，理解“谁写入它、谁继续消费它”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisplayStyleBO {

	/**
	 * 图表类型，如：table, bar, line, pie等
	 */
	@JsonPropertyDescription("图表类型，取值范围：table、 bar、 line、 pie等")
	private String type;

	/**
	 * 图表标题
	 */
	@JsonPropertyDescription("图表标题")
	private String title;

	/**
	 * X轴字段名
	 */
	@JsonPropertyDescription("X轴字段名")
	private String x;

	/**
	 * Y轴字段名列表
	 */
	@JsonPropertyDescription("Y轴字段名列表")
	private List<String> y;

}
