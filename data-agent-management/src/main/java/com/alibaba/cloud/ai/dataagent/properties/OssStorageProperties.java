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
package com.alibaba.cloud.ai.dataagent.properties;

import com.alibaba.cloud.ai.dataagent.constant.Constant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OssStorageProperties：配置属性绑定类。
 *
 * 它负责把 application.yml 中的OSSStorage配置映射成可注入对象，供运行时统一读取。
 * 学习时重点看配置前缀、默认值，以及这些参数会影响哪一段业务链路。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = Constant.PROJECT_PROPERTIES_PREFIX + ".file.oss")
public class OssStorageProperties {

	/**
	 * OSS访问密钥ID
	 */
	private String accessKeyId;

	/**
	 * OSS访问密钥Secret
	 */
	private String accessKeySecret;

	/**
	 * OSS端点地址
	 */
	private String endpoint;

	/**
	 * OSS存储桶名称
	 */
	private String bucketName;

	/**
	 * 自定义域名（可选）
	 */
	private String customDomain;

}
