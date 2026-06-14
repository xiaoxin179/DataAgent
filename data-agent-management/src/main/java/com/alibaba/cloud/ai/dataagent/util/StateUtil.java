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
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;

/**
 * Graph 状态读取工具。
 *
 * {@link OverAllState} 内部按 key 保存 Object，本类把常见的取值、类型转换和默认值逻辑集中起来，
 * 避免每个节点都重复编写 Optional 判断和强制类型转换。
 *
 * @author zhangshenghang
 */
public class StateUtil {

	// 复用项目统一配置的 ObjectMapper，把 Graph 反序列化出的 Map 重新转换成具体 DTO。
	private static final ObjectMapper OBJECT_MAPPER = JsonUtil.getObjectMapper();

	/**
	 * 读取必填字符串；key 不存在时立即抛错，避免节点在缺少必要状态时继续运行。
	 */
	public static String getStringValue(OverAllState state, String key) {
		// value(key) 返回 Optional，因此先映射为 String，再统一处理缺失情况。
		return state.value(key)
			.map(String.class::cast)
			.orElseThrow(() -> new IllegalStateException("State key not found: " + key));
	}

	/**
	 * 读取可选字符串；key 不存在时返回调用方提供的默认值。
	 */
	public static String getStringValue(OverAllState state, String key, String defaultValue) {
		// 与必填版本不同，这里不抛异常，适合多轮上下文等可缺省状态。
		return state.value(key).map(String.class::cast).orElse(defaultValue);
	}

	/**
	 * 读取必填列表。
	 *
	 * Graph 状态只保留运行时 Object 类型，Java 泛型会被擦除，因此这里需要一次受控的强制转换。
	 */
	@SuppressWarnings("unchecked")
	public static <T> List<T> getListValue(OverAllState state, String key) {
		// 调用方通过变量的泛型类型决定 T，状态缺失时给出包含 key 的明确异常。
		return state.value(key)
			.map(v -> (List<T>) v)
			.orElseThrow(() -> new IllegalStateException("State key not found: " + key));
	}

	/**
	 * 读取必填对象，并在状态值是 Map 时恢复成目标 DTO。
	 */
	public static <T> T getObjectValue(OverAllState state, String key, Class<T> type) {
		// deserializeIfNeeded 同时兼容内存中的 DTO 和持久化恢复后的 HashMap。
		return state.value(key)
			.map(value -> deserializeIfNeeded(value, type))
			.orElseThrow(() -> new IllegalStateException("State key not found: " + key));
	}

	/**
	 * 读取可选对象；状态缺失时直接返回默认对象。
	 */
	public static <T> T getObjectValue(OverAllState state, String key, Class<T> type, T defaultValue) {
		// 状态存在时仍然执行 DTO 恢复，只有完全不存在时才使用 defaultValue。
		return state.value(key).map(value -> deserializeIfNeeded(value, type)).orElse(defaultValue);
	}

	/**
	 * 根据状态值的实际类型决定直接返回、Map 转 DTO，还是普通强制转换。
	 */
	private static <T> T deserializeIfNeeded(Object value, Class<T> type) {
		// 同一次内存执行中通常已经是目标类型，不需要额外序列化。
		if (type.isInstance(value)) {
			return type.cast(value);
		}

		// Graph 从检查点恢复状态后，DTO 可能退化成 HashMap，此时让 ObjectMapper 按字段重建对象。
		if (value instanceof HashMap && !type.equals(HashMap.class)) {
			return OBJECT_MAPPER.convertValue(value, type);
		}

		// String、Integer、Boolean 等普通值走标准类型检查转换；类型不匹配时会抛 ClassCastException。
		return type.cast(value);
	}

	/**
	 * 读取可选对象，并在缺失时延迟创建默认对象。
	 *
	 * Supplier 只会在 key 不存在时执行，适合默认对象创建成本较高的场景。
	 */
	public static <T> T getObjectValue(OverAllState state, String key, Class<T> type, Supplier<T> defaultSupplier) {
		// 该重载主要用于状态本身已经是目标类型的场景，因此直接使用 type::cast。
		return state.value(key).map(type::cast).orElseGet(defaultSupplier);
	}

	/**
	 * 判断状态中是否存在可使用的值。
	 *
	 * 对字符串额外排除空串；对集合、DTO、数字等其他对象，只要存在就视为有效。
	 */
	public static boolean hasValue(OverAllState state, String key) {
		// 先读取 Optional，避免对不存在的 key 直接调用 get。
		Optional<Object> value = state.value(key);
		if (value.isPresent()) {
			// 空字符串通常代表上一个节点尚未真正产生结果，不能仅凭 Optional 存在就判定有效。
			if (value.get() instanceof String content) {
				return StringUtils.isNotEmpty(content);
			}
			// 非字符串对象存在即可认为该状态已经写入。
			return true;
		}
		// key 不存在。
		return false;
	}

	/**
	 * 读取 Spring AI Document 列表，为 Schema、Evidence 等召回节点提供语义更明确的入口。
	 */
	public static List<Document> getDocumentList(OverAllState state, String key) {
		// 实际读取规则与普通列表一致，只是固定了元素类型。
		return getListValue(state, key);
	}

	/**
	 * 从问题增强节点的 DTO 中取得后续流程统一使用的规范化问题。
	 */
	public static String getCanonicalQuery(OverAllState state) {
		// QUERY_ENHANCE_NODE_OUTPUT 是问题增强节点写入 Graph 的结构化结果。
		QueryEnhanceOutputDTO queryEnhanceOutputDTO = getObjectValue(state, QUERY_ENHANCE_NODE_OUTPUT,
				QueryEnhanceOutputDTO.class);
		// 后续 Schema 召回、规划和 SQL 生成都使用 canonicalQuery，而不是直接使用用户原始输入。
		return queryEnhanceOutputDTO.getCanonicalQuery();
	}

}
