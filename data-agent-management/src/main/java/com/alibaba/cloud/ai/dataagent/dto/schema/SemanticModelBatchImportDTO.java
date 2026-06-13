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
package com.alibaba.cloud.ai.dataagent.dto.schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SemanticModelBatchImportDTO：请求参数或中间结果传输对象。
 *
 * 它主要负责承载语义模型BatchImport相关字段，本身不放复杂业务逻辑。
 * 阅读时重点看字段语义、默认值，以及这些字段最终会在哪一层被消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticModelBatchImportDTO {

	@NotNull(message = "智能体ID不能为空")
	private Long agentId;

	@NotEmpty(message = "导入数据不能为空")
	@Valid
	private List<SemanticModelImportItem> items;

}
