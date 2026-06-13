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
package com.alibaba.cloud.ai.dataagent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PromptLoader：提示词装配相关组件。
 *
 * 它负责维护或拼接提示词Loader相关 Prompt，让模型调用保持可复用、可调试、可配置。
 * 重点看模板变量来自哪里，以及这些变量分别在哪一步准备好。
 */
@Slf4j
public class PromptLoader {

	private static final String PROMPT_PATH_PREFIX = "prompts/";

	private static final ConcurrentHashMap<String, String> promptCache = new ConcurrentHashMap<>();

	/**
	 * Load prompt template from file
	 * @param promptName prompt file name (without path and extension)
	 * @return prompt content
	 */
	public static String loadPrompt(String promptName) {
		return promptCache.computeIfAbsent(promptName, name -> {
			String fileName = PROMPT_PATH_PREFIX + name + ".txt";
			// 使用本类的类加载器获取资源（避免jar包中无法获取资源）
			try (InputStream inputStream = PromptLoader.class.getClassLoader().getResourceAsStream(fileName)) {
				return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
			}
			catch (IOException e) {
				log.error("加载提示词失败！{}", e.getMessage(), e);
				throw new RuntimeException("加载提示词失败: " + name, e);
			}
		});
	}

	/**
 * `clearCache`：清理临时状态、资源或历史数据。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	public static void clearCache() {
		promptCache.clear();
	}

	/**
	 * Get cache size
	 * @return number of prompts in cache
	 */
	public static int getCacheSize() {
		return promptCache.size();
	}

}
