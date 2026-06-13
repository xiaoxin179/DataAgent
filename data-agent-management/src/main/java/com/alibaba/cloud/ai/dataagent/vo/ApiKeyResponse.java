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
package com.alibaba.cloud.ai.dataagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ApiKeyResponse：接口返回视图对象。
 *
 * 它把内部API密钥响应结果整理成更适合前端或调用方读取的输出结构。
 * 学习这类类时，重点关注哪些字段是展示用、哪些字段承担流程控制作用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyResponse {

	private String apiKey;

	private Integer apiKeyEnabled;

}
