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

import com.alibaba.cloud.ai.dataagent.enums.CodePoolExecutorEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PROJECT_PROPERTIES_PREFIX;

/**
 * CodeExecutorProperties：配置属性绑定类。
 *
 * 它负责把 application.yml 中的代码执行配置映射成可注入对象，供运行时统一读取。
 * 学习时重点看配置前缀、默认值，以及这些参数会影响哪一段业务链路。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = CodeExecutorProperties.CONFIG_PREFIX)
public class CodeExecutorProperties {

	public static final String CONFIG_PREFIX = PROJECT_PROPERTIES_PREFIX + ".code-executor";

	/**
	 * 指定代码容器池运行时服务的实现类型
	 */
	CodePoolExecutorEnum codePoolExecutor = CodePoolExecutorEnum.DOCKER;

	/**
	 * 服务主机地址，如果为 null 则使用默认地址
	 */
	String host = null;

	/**
	 * 镜像名称，可以自定义一个包含常用第三方依赖的镜像来替代这个配置
	 */
	String imageName = "continuumio/anaconda3:latest";

	/**
	 * 容器名称前缀
	 */
	String containerNamePrefix = "nl2sql-python-exec-";

	/**
	 * 任务阻塞队列大小
	 */
	Integer taskQueueSize = 5;

	/**
	 * 核心容器最大数量
	 */
	Integer coreContainerNum = 2;

	/**
	 * 临时容器最大数量
	 */
	Integer tempContainerNum = 2;

	/**
	 * 线程池核心线程数
	 */
	Integer coreThreadSize = 5;

	/**
	 * 线程池最大线程数
	 */
	Integer maxThreadSize = 5;

	/**
	 * 临时容器存活时间，单位分钟
	 */
	Integer tempContainerAliveTime = 5;

	/**
	 * 线程池任务存活时间，单位秒
	 */
	Long keepThreadAliveTime = 60L;

	/**
	 * 线程池任务阻塞队列大小
	 */
	Integer threadQueueSize = 10;

	/**
	 * 容器最大内存，单位 MB
	 */
	Long limitMemory = 500L;

	/**
	 * 容器 CPU 核数
	 */
	Long cpuCore = 1L;

	/**
	 * Python 代码执行时间限制
	 */
	String codeTimeout = "60s";

	/**
	 * 容器最大运行时长
	 */
	Long containerTimeout = 3000L;

	/**
	 * 容器网络模式
	 */
	String networkMode = "none";

	/**
	 * Python执行的最大重试次数
	 */
	Integer pythonMaxTriesCount = 5;

}
