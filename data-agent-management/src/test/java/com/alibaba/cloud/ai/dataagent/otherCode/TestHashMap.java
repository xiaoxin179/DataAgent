package com.alibaba.cloud.ai.dataagent.otherCode;

import java.util.concurrent.ConcurrentHashMap;

public class TestHashMap {

	public static void main(String[] args) {
		ConcurrentHashMap<String, String> concurrentHashMap = new ConcurrentHashMap<>();
		concurrentHashMap.put("existing", "原有值");

		String existingValue = concurrentHashMap.computeIfAbsent("existing", key -> "不会写入的新值");
		System.out.println("键已存在：" + existingValue);

		String createdValue = concurrentHashMap.computeIfAbsent("missing", key -> "计算得到的新值");
		System.out.println("键不存在：" + createdValue);
		System.out.println("最终 Map：" + concurrentHashMap);
	}

}
