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
package com.alibaba.cloud.ai.dataagent.dto;

import lombok.Data;

/**
 * ChatMessageDTO：请求参数或中间结果传输对象。
 *
 * 它主要负责承载对话消息相关字段，本身不放复杂业务逻辑。
 * 阅读时重点看字段语义、默认值，以及这些字段最终会在哪一层被消费。
 */
@Data
public class ChatMessageDTO {

	private String role;

	private String content;

	private String messageType;

	private String metadata;

	/**
	 * Flag from frontend to trigger async title generation for newly created sessions.
	 */
	private boolean titleNeeded;

}
