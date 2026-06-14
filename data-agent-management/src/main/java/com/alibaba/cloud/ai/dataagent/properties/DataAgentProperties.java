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

// 提供本项目统一的配置前缀，避免在多个类中重复写配置字符串。
import com.alibaba.cloud.ai.dataagent.constant.Constant;
// 表示大模型采用流式调用还是阻塞式调用。
import com.alibaba.cloud.ai.dataagent.service.llm.LlmServiceEnum;
// Lombok 根据字段自动生成 getter 方法。
import lombok.Getter;
// Lombok 根据字段自动生成 setter 方法，Spring 绑定配置时会调用这些 setter。
import lombok.Setter;
// 告诉 Spring Boot：把指定前缀下的配置绑定到当前类。
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DataAgent 的集中配置对象。
 *
 * <p>
 * Spring Boot 启动时，会把 {@code application.yml} 中
 * {@code spring.ai.alibaba.data-agent} 前缀下的配置，按照名称映射到这个对象的字段中。例如：
 *
 * <pre>
 * spring.ai.alibaba.data-agent.vector-store.default-topk-limit
 * </pre>
 *
 * 会被绑定到 {@code vectorStore.defaultTopkLimit}。业务类只需要注入
 * {@code DataAgentProperties}，不必反复使用 {@code @Value} 读取零散配置。
 */
// 为当前类及其嵌套配置类生成所有字段的 getter。
@Getter
// 为当前类及其嵌套配置类生成所有字段的 setter。
@Setter
// Constant.PROJECT_PROPERTIES_PREFIX 的值是 spring.ai.alibaba.data-agent。
@ConfigurationProperties(prefix = Constant.PROJECT_PROPERTIES_PREFIX)
public class DataAgentProperties {

	/**
	 * 大模型调用方式，默认使用 STREAM 流式输出。
	 *
	 * <p>
	 * LlmServiceFactory 会读取该值，决定创建流式实现还是阻塞式实现。
	 */
	private LlmServiceEnum llmServiceType = LlmServiceEnum.STREAM;

	/**
	 * Embedding 批处理配置。
	 *
	 * <p>
	 * 这里直接创建默认对象，意味着 YAML 没有配置 embedding-batch 时也不会得到 null，
	 * 而是继续使用 EmbeddingBatch 中声明的默认值。
	 */
	private EmbeddingBatch embeddingBatch = new EmbeddingBatch();

	/**
	 * 向量库查询、删除和本地持久化相关配置。
	 */
	private VectorStoreProperties vectorStore = new VectorStoreProperties();

	/**
	 * HTML 报告模板依赖的前端脚本地址配置。
	 */
	private ReportTemplate reportTemplate = new ReportTemplate();

	/**
	 * SQL 执行失败后允许重新生成并重试的最大次数，默认 10 次。
	 */
	private int maxSqlRetryCount = 10;

	/**
	 * SQL 质量不达标时允许继续优化的最大次数，默认 10 次。
	 */
	private int maxSqlOptimizeCount = 10;

	/**
	 * SQL 优化结果的目标分数阈值，达到该值后可停止继续优化。
	 */
	private double sqlScoreThreshold = 0.95;

	/**
	 * 文档切分策略的公共配置和各策略专属配置。
	 */
	private TextSplitter textSplitter = new TextSplitter();

	/**
	 * 多轮对话最多保留的历史轮数，默认保留最近 5 轮。
	 *
	 * <p>
	 * MultiTurnContextManager 会使用它裁剪旧对话，避免上下文无限增长。
	 */
	private int maxturnhistory = 5;

	/**
	 * 单次规划结果允许保留的最大字符长度，默认 2000。
	 */
	private int maxplanlength = 2000;

	/**
	 * 构建数据库 Schema 上下文时，每张表允许估算或处理的最大列数。
	 */
	private int maxColumnsPerTable = 50;

	/**
	 * 是否分析 SQL 查询结果并判断是否需要生成图表，默认开启。
	 */
	private boolean enableSqlResultChart = true;

	/**
	 * 对 SQL 结果进行图表增强时的超时时间，单位为毫秒，默认 3000 毫秒。
	 */
	private Long enrichSqlResultTimeout = 3000L;

	/**
	 * 报告模板使用的 JavaScript 资源地址。
	 *
	 * <p>
	 * static 表示它只是 DataAgentProperties 名下的一组配置，不需要依赖外部类实例。
	 */
	@Getter
	@Setter
	public static class ReportTemplate {

		/**
		 * Marked.js 的访问地址，用于在浏览器中把 Markdown 报告解析为 HTML。
		 */
		private String markedUrl = "https://mirrors.sustech.edu.cn/cdnjs/ajax/libs/marked/12.0.0/marked.min.js";

		/**
		 * ECharts 的访问地址，用于在浏览器中渲染 SQL 分析图表。
		 */
		private String echartsUrl = "https://mirrors.sustech.edu.cn/cdnjs/ajax/libs/echarts/5.5.0/echarts.min.js";

	}

	/**
	 * 文档切分配置。
	 *
	 * <p>
	 * 文档进入向量库前通常不能整篇直接生成向量，需要按 token、句子、段落或语义切成多个小块。
	 */
	@Getter
	@Setter
	public static class TextSplitter {

		/**
		 * 每个文本块的目标大小，主要按 token 数量理解，默认 1000。
		 */
		private int chunkSize = 1000;

		/**
		 * 按 token 数量切分文本时使用的专属配置。
		 */
		private TokenTextSplitterConfig token = new TokenTextSplitterConfig();

		/**
		 * 按递归字符规则切分文本时使用的专属配置。
		 */
		private RecursiveTextSplitterConfig recursive = new RecursiveTextSplitterConfig();

		/**
		 * 按完整句子切分文本时使用的专属配置。
		 */
		private SentenceTextSplitterConfig sentence = new SentenceTextSplitterConfig();

		/**
		 * 根据相邻文本语义变化切分文本时使用的专属配置。
		 */
		private SemanticTextSplitterConfig semantic = new SemanticTextSplitterConfig();

		/**
		 * 按自然段落切分文本时使用的专属配置。
		 */
		private ParagraphTextSplitterConfig paragraph = new ParagraphTextSplitterConfig();

		/**
		 * TokenTextSplitter 的配置。
		 */
		@Getter
		@Setter
		public static class TokenTextSplitterConfig {

			/**
			 * 一个文本块至少应包含的字符数，默认 400，避免产生过于零碎的块。
			 */
			private int minChunkSizeChars = 400;

			/**
			 * 文本块达到多少长度后才值得生成 Embedding，默认 10。
			 */
			private int minChunkLengthToEmbed = 10;

			/**
			 * 单篇文档最多允许切出的文本块数量，默认 5000，防止异常文本造成无限切分。
			 */
			private int maxNumChunks = 5000;

			/**
			 * 切分后是否把原分隔符保留在文本块中，默认保留，以减少语义信息丢失。
			 */
			private boolean keepSeparator = true;

		}

		/**
		 * RecursiveCharacterTextSplitter 的配置。
		 *
		 * <p>
		 * 该策略会按多个分隔符逐级尝试切分，尽量在不超过块大小的同时保留自然文本结构。
		 */
		@Getter
		@Setter
		public static class RecursiveTextSplitterConfig {

			/**
			 * 相邻文本块之间预留的重叠字符数，默认 200，用于降低切分边界造成的上下文丢失。
			 */
			private int chunkOverlap = 200;

			/**
			 * 自定义分隔符列表；为 null 时使用切分器内部的默认分隔符。
			 */
			private String[] separators = null;

		}

		/**
		 * SentenceSplitter 的配置。
		 */
		@Getter
		@Setter
		public static class SentenceTextSplitterConfig {

			/**
			 * 相邻文本块重叠的句子数量，默认保留前一个块的最后 1 个句子。
			 */
			private int sentenceOverlap = 1;

		}

		/**
		 * SemanticTextSplitter 的配置。
		 *
		 * <p>
		 * 该策略借助 Embedding 判断相邻内容是否仍属于同一语义主题。
		 */
		@Getter
		@Setter
		public static class SemanticTextSplitterConfig {

			/**
			 * 语义文本块的最小大小，默认 200，避免文本块太短。
			 */
			private int minChunkSize = 200;

			/**
			 * 语义文本块的最大大小，默认 1000，避免单个文本块过长。
			 */
			private int maxChunkSize = 1000;

			/**
			 * 判断语义是否连续的相似度阈值，默认 0.5；阈值越高，越容易在语义变化处切开。
			 */
			private double similarityThreshold = 0.5;

		}

		/**
		 * ParagraphTextSplitter 的配置。
		 */
		@Getter
		@Setter
		public static class ParagraphTextSplitterConfig {

			/**
			 * 相邻段落块之间重叠的字符数，默认 200；这里是字符数，不是段落数量。
			 */
			private int paragraphOverlapChars = 200;

		}

	}

	/**
	 * 批量调用 Embedding API 时的拆批规则。
	 *
	 * <p>
	 * 拆批的目的既是提高吞吐量，也是避免一次请求超过模型的 token 或文本数量限制。
	 */
	@Getter
	@Setter
	public static class EmbeddingBatch {

		/**
		 * 统计 token 时使用的编码方式，默认 cl100k_base，适合 OpenAI 系列模型。
		 */
		private String encodingType = "cl100k_base";

		/**
		 * 单个批次允许包含的最大 token 数，默认 8000。
		 *
		 * <p>
		 * 值越大通常吞吐量越高，但也越容易超过 Embedding API 的请求限制。
		 */
		private int maxTokenCount = 8000;

		/**
		 * 为 token 上限预留的安全比例，默认 0.2，即只使用约 80% 的理论容量。
		 */
		private double reservePercentage = 0.2;

		/**
		 * 单个批次允许提交的最大文本条数，默认 10，用于适配 DashScope 等接口限制。
		 */
		private int maxTextCount = 10;

	}

	/**
	 * 向量库相关配置。
	 *
	 * <p>
	 * TopK 决定“最多取多少条”，相似度阈值决定“最低要多相关”，两者共同控制召回结果。
	 */
	@Getter
	@Setter
	public static class VectorStoreProperties {

		/**
		 * 召回数据库表信息时最多返回的表数量，默认 10。
		 */
		private int tableTopkLimit = 10;

		/**
		 * 表召回的最低相似度，默认 0.2；设置较低是为了减少漏掉相关表的概率。
		 */
		private double tableSimilarityThreshold = 0.2;

		/**
		 * 普通知识和业务术语检索的默认相似度阈值，默认 0.4。
		 */
		private double defaultSimilarityThreshold = 0.4;

		/**
		 * 普通知识和业务术语检索最多返回的文档数量，默认 8。
		 */
		private int defaultTopkLimit = 8;

		/**
		 * 一次批量删除操作最多查询并删除的向量文档数量，默认 5000。
		 */
		private int batchDelTopkLimit = 5000;

		/**
		 * 是否同时使用向量检索和关键词检索，默认只使用向量检索。
		 */
		private boolean enableHybridSearch = false;

		/**
		 * Elasticsearch 关键词检索结果的最低相关性分数，默认 0.5。
		 */
		private double elasticsearchMinScore = 0.5;

		/**
		 * SimpleVectorStore 保存和加载本地 JSON 数据时使用的文件路径。
		 */
		private String filePath = "./vectorstore/vectorstore.json";

	}

}
