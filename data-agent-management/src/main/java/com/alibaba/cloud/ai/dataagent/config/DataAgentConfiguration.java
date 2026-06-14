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
package com.alibaba.cloud.ai.dataagent.config;

import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.properties.FileStorageProperties;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.SimpleVectorStoreInitialization;
import com.alibaba.cloud.ai.dataagent.splitter.SentenceSplitter;
import com.alibaba.cloud.ai.transformer.splitter.RecursiveCharacterTextSplitter;
import com.alibaba.cloud.ai.dataagent.splitter.SemanticTextSplitter;
import com.alibaba.cloud.ai.dataagent.splitter.ParagraphTextSplitter;
import com.alibaba.cloud.ai.dataagent.util.McpServerToolUtil;
import com.alibaba.cloud.ai.dataagent.util.NodeBeanUtil;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.strategy.EnhancedTokenCountBatchingStrategy;
import com.alibaba.cloud.ai.dataagent.workflow.dispatcher.*;
import com.alibaba.cloud.ai.dataagent.workflow.node.*;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.SpringBeanToolCallbackResolver;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * DataAgent的自动配置类
 *
 * @author vlsmb
 * @since 2025/9/28
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties({ CodeExecutorProperties.class, DataAgentProperties.class, FileStorageProperties.class })
public class DataAgentConfiguration implements DisposableBean {

	/**
	 * 专用线程池，用于数据库操作的并行处理
	 */
	private ExecutorService dbOperationExecutor;

	@Bean
	@ConditionalOnMissingBean(RestClientCustomizer.class)
	public RestClientCustomizer restClientCustomizer(@Value("${rest.connect.timeout:600}") long connectTimeout,
			@Value("${rest.read.timeout:600}") long readTimeout) {
		// 返回一个定制器，由 Spring 在创建同步 RestClient 时应用连接和读取超时。
		return restClientBuilder -> restClientBuilder
			.requestFactory(ClientHttpRequestFactoryBuilder.reactor().withCustomizer(factory -> {
				// 限制建立 TCP 连接允许等待的最长时间。
				factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));

				// 限制连接建立后读取响应允许等待的最长时间。
				factory.setReadTimeout(Duration.ofSeconds(readTimeout));
			}).build());
	}

	@Bean
	@ConditionalOnMissingBean(WebClient.Builder.class)
	public WebClient.Builder webClientBuilder(@Value("${webclient.response.timeout:600}") long responseTimeout) {

		// WebClient 用于异步/流式模型调用，底层 Reactor Netty 客户端负责响应超时。
		return WebClient.builder()
			.clientConnector(new ReactorClientHttpConnector(
					HttpClient.create().responseTimeout(Duration.ofSeconds(responseTimeout))));
	}

	@Bean
	public StateGraph nl2sqlGraph(NodeBeanUtil nodeBeanUtil, CodeExecutorProperties codeExecutorProperties)
			throws GraphStateException {

		// 定义每个 state key 在节点返回新值时如何与旧值合并。
		KeyStrategyFactory keyStrategyFactory = () -> {
			// key 是状态名，value 是对应的合并策略。
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			// User input
			keyStrategyHashMap.put(INPUT_KEY, KeyStrategy.REPLACE);
			// Agent ID
			keyStrategyHashMap.put(AGENT_ID, KeyStrategy.REPLACE);
			// Multi-turn context
			keyStrategyHashMap.put(MULTI_TURN_CONTEXT, KeyStrategy.REPLACE);
			// Intent recognition
			keyStrategyHashMap.put(INTENT_RECOGNITION_NODE_OUTPUT, KeyStrategy.REPLACE);
			// QUERY_ENHANCE_NODE节点输出
			keyStrategyHashMap.put(QUERY_ENHANCE_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Semantic model
			keyStrategyHashMap.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, KeyStrategy.REPLACE);
			// EVIDENCE节点输出
			keyStrategyHashMap.put(EVIDENCE, KeyStrategy.REPLACE);
			// schema recall节点输出
			keyStrategyHashMap.put(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, KeyStrategy.REPLACE);
			// table relation节点输出
			keyStrategyHashMap.put(TABLE_RELATION_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(TABLE_RELATION_EXCEPTION_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(TABLE_RELATION_RETRY_COUNT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(DB_DIALECT_TYPE, KeyStrategy.REPLACE);
			// Feasibility Assessment 节点输出
			keyStrategyHashMap.put(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, KeyStrategy.REPLACE);
			// sql generate节点输出
			keyStrategyHashMap.put(SQL_GENERATE_SCHEMA_MISSING_ADVICE, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_GENERATE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_GENERATE_COUNT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(SQL_REGENERATE_REASON, KeyStrategy.REPLACE);
			// Semantic consistence节点输出
			keyStrategyHashMap.put(SEMANTIC_CONSISTENCY_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Planner 节点输出
			keyStrategyHashMap.put(PLANNER_NODE_OUTPUT, KeyStrategy.REPLACE);
			// PlanExecutorNode
			keyStrategyHashMap.put(PLAN_CURRENT_STEP, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_NEXT_NODE, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_VALIDATION_STATUS, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_VALIDATION_ERROR, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PLAN_REPAIR_COUNT, KeyStrategy.REPLACE);
			// SQL Execute 节点输出
			keyStrategyHashMap.put(SQL_EXECUTE_NODE_OUTPUT, KeyStrategy.REPLACE);
			// Python代码运行相关
			keyStrategyHashMap.put(SQL_RESULT_LIST_MEMORY, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_IS_SUCCESS, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_TRIES_COUNT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_FALLBACK_MODE, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_EXECUTE_NODE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_GENERATE_NODE_OUTPUT, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(PYTHON_ANALYSIS_NODE_OUTPUT, KeyStrategy.REPLACE);
			// NL2SQL相关
			keyStrategyHashMap.put(IS_ONLY_NL2SQL, KeyStrategy.REPLACE);
			// Human Review keys
			keyStrategyHashMap.put(HUMAN_REVIEW_ENABLED, KeyStrategy.REPLACE);
			keyStrategyHashMap.put(HUMAN_FEEDBACK_DATA, KeyStrategy.REPLACE);
			// Langfuse 追踪：threadId 透传
			keyStrategyHashMap.put(TRACE_THREAD_ID, KeyStrategy.REPLACE);
			// Final result
			keyStrategyHashMap.put(RESULT, KeyStrategy.REPLACE);
			// 返回完整策略表，StateGraph 后续用它合并每个节点返回的 Map。
			return keyStrategyHashMap;
		};

		// 创建图并注册全部业务节点；NodeBeanUtil 从 Spring 容器取得节点 Bean 并适配为异步动作。
		StateGraph stateGraph = new StateGraph(NL2SQL_GRAPH_NAME, keyStrategyFactory)
			.addNode(INTENT_RECOGNITION_NODE, nodeBeanUtil.getNodeBeanAsync(IntentRecognitionNode.class))
			.addNode(EVIDENCE_RECALL_NODE, nodeBeanUtil.getNodeBeanAsync(EvidenceRecallNode.class))
			.addNode(QUERY_ENHANCE_NODE, nodeBeanUtil.getNodeBeanAsync(QueryEnhanceNode.class))
			.addNode(SCHEMA_RECALL_NODE, nodeBeanUtil.getNodeBeanAsync(SchemaRecallNode.class))
			.addNode(TABLE_RELATION_NODE, nodeBeanUtil.getNodeBeanAsync(TableRelationNode.class))
			.addNode(FEASIBILITY_ASSESSMENT_NODE, nodeBeanUtil.getNodeBeanAsync(FeasibilityAssessmentNode.class))
			.addNode(SQL_GENERATE_NODE, nodeBeanUtil.getNodeBeanAsync(SqlGenerateNode.class))
			.addNode(PLANNER_NODE, nodeBeanUtil.getNodeBeanAsync(PlannerNode.class))
			.addNode(PLAN_EXECUTOR_NODE, nodeBeanUtil.getNodeBeanAsync(PlanExecutorNode.class))
			.addNode(SQL_EXECUTE_NODE, nodeBeanUtil.getNodeBeanAsync(SqlExecuteNode.class))
			.addNode(PYTHON_GENERATE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonGenerateNode.class))
			.addNode(PYTHON_EXECUTE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonExecuteNode.class))
			.addNode(PYTHON_ANALYZE_NODE, nodeBeanUtil.getNodeBeanAsync(PythonAnalyzeNode.class))
			.addNode(REPORT_GENERATOR_NODE, nodeBeanUtil.getNodeBeanAsync(ReportGeneratorNode.class))
			.addNode(SEMANTIC_CONSISTENCY_NODE, nodeBeanUtil.getNodeBeanAsync(SemanticConsistencyNode.class))
			.addNode(HUMAN_FEEDBACK_NODE, nodeBeanUtil.getNodeBeanAsync(HumanFeedbackNode.class));

		// 从 START 进入意图识别；后续固定边直接跳转，条件边通过 Dispatcher 读取 state 决策。
		stateGraph.addEdge(START, INTENT_RECOGNITION_NODE)
			.addConditionalEdges(INTENT_RECOGNITION_NODE, edge_async(new IntentRecognitionDispatcher()),
					Map.of(EVIDENCE_RECALL_NODE, EVIDENCE_RECALL_NODE, END, END))
			.addEdge(EVIDENCE_RECALL_NODE, QUERY_ENHANCE_NODE)
			.addConditionalEdges(QUERY_ENHANCE_NODE, edge_async(new QueryEnhanceDispatcher()),
					Map.of(SCHEMA_RECALL_NODE, SCHEMA_RECALL_NODE, END, END))
			.addConditionalEdges(SCHEMA_RECALL_NODE, edge_async(new SchemaRecallDispatcher()),
					Map.of(TABLE_RELATION_NODE, TABLE_RELATION_NODE, END, END))

			.addConditionalEdges(TABLE_RELATION_NODE, edge_async(new TableRelationDispatcher()),
					Map.of(FEASIBILITY_ASSESSMENT_NODE, FEASIBILITY_ASSESSMENT_NODE, END, END, TABLE_RELATION_NODE,
							TABLE_RELATION_NODE)) // retry
			.addConditionalEdges(FEASIBILITY_ASSESSMENT_NODE, edge_async(new FeasibilityAssessmentDispatcher()),
					Map.of(PLANNER_NODE, PLANNER_NODE, END, END))

			// Planner 只生成计划，PlanExecutor 负责校验并选择当前步骤对应的执行节点。
			.addEdge(PLANNER_NODE, PLAN_EXECUTOR_NODE)
			// Python 代码先生成再执行，执行结果由 Dispatcher 决定重试、分析或结束。
			.addEdge(PYTHON_GENERATE_NODE, PYTHON_EXECUTE_NODE)
			.addConditionalEdges(PYTHON_EXECUTE_NODE, edge_async(new PythonExecutorDispatcher(codeExecutorProperties)),
					Map.of(PYTHON_ANALYZE_NODE, PYTHON_ANALYZE_NODE, END, END, PYTHON_GENERATE_NODE,
							PYTHON_GENERATE_NODE))
			.addEdge(PYTHON_ANALYZE_NODE, PLAN_EXECUTOR_NODE)
			// 计划执行器可以进入 SQL、Python、报告、人工反馈，也可以回 Planner 修复计划。
			.addConditionalEdges(PLAN_EXECUTOR_NODE, edge_async(new PlanExecutorDispatcher()), Map.of(
					// 计划校验失败时回 Planner 生成修正版。
					PLANNER_NODE, PLANNER_NODE,
					// 计划校验成功时按当前 ExecutionStep 选择具体工具节点。
					SQL_GENERATE_NODE, SQL_GENERATE_NODE, PYTHON_GENERATE_NODE, PYTHON_GENERATE_NODE,
					REPORT_GENERATOR_NODE, REPORT_GENERATOR_NODE,
					// 开启人工审核时先进入反馈节点。
					HUMAN_FEEDBACK_NODE, HUMAN_FEEDBACK_NODE,
					// 修复次数超限或计划已结束时进入 END。
					END, END))
			// 人工反馈节点根据批准/拒绝结果决定继续执行还是回 Planner。
			.addConditionalEdges(HUMAN_FEEDBACK_NODE, edge_async(new HumanFeedbackDispatcher()), Map.of(
					// 拒绝计划时重新规划。
					PLANNER_NODE, PLANNER_NODE,
					// 批准计划时回到执行器处理当前步骤。
					PLAN_EXECUTOR_NODE, PLAN_EXECUTOR_NODE,
					// 无法继续时结束图执行。
					END, END))
			// 最终报告生成后整张图完成。
			.addEdge(REPORT_GENERATOR_NODE, END)
			// SQL 生成后先做语义一致性检查，失败可回生成节点，成功才执行。
			.addConditionalEdges(SQL_GENERATE_NODE, nodeBeanUtil.getEdgeBeanAsync(SqlGenerateDispatcher.class),
					Map.of(SQL_GENERATE_NODE, SQL_GENERATE_NODE, END, END, SEMANTIC_CONSISTENCY_NODE,
							SEMANTIC_CONSISTENCY_NODE))
			.addConditionalEdges(SEMANTIC_CONSISTENCY_NODE, edge_async(new SemanticConsistenceDispatcher()),
					Map.of(SQL_GENERATE_NODE, SQL_GENERATE_NODE, SQL_EXECUTE_NODE, SQL_EXECUTE_NODE))
			.addConditionalEdges(SQL_EXECUTE_NODE, edge_async(new SQLExecutorDispatcher()),
					Map.of(SQL_GENERATE_NODE, SQL_GENERATE_NODE, PLAN_EXECUTOR_NODE, PLAN_EXECUTOR_NODE));

		// 导出 PlantUML 文本，启动日志中可以直接检查实际注册的节点和边。
		GraphRepresentation graphRepresentation = stateGraph.getGraph(GraphRepresentation.Type.PLANTUML,
				"workflow graph");

		// 打印图结构，便于开发阶段核对配置是否符合预期。
		log.info("workflow in PlantUML format as follows \n\n" + graphRepresentation.content() + "\n\n");

		// 返回未编译的 StateGraph，GraphServiceImpl 构造时再附加人工中断配置并编译。
		return stateGraph;
	}

	/**
	 * 为了不必要的重复手动配置，不要在此添加其他向量的手动配置，如果扩展其他向量，请阅读spring ai文档
	 * <a href="https://springdoc.cn/spring-ai/api/vectordbs.html">...</a>
	 * 根据自己想要的向量，在pom文件引入 Boot Starter 依赖即可。此处配置使用内存向量作为兜底配置
	 */
	@Primary
	@Bean
	@ConditionalOnMissingBean(VectorStore.class)
	@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "simple", matchIfMissing = true)
	public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
		// 当项目没有配置外部 VectorStore 时，使用内存实现保证应用仍可启动和运行。
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	@ConditionalOnBean(SimpleVectorStore.class)
	public SimpleVectorStoreInitialization simpleVectorStoreInitialization(SimpleVectorStore vectorStore,
			DataAgentProperties properties) {
		// 创建初始化器，负责在应用启动阶段加载或准备 SimpleVectorStore 数据。
		return new SimpleVectorStoreInitialization(vectorStore, properties);
	}

	@Bean
	@ConditionalOnMissingBean(BatchingStrategy.class)
	public BatchingStrategy customBatchingStrategy(DataAgentProperties properties) {
		// 使用增强的批处理策略，同时考虑token数量和文本数量限制
		EncodingType encodingType;
		try {
			// 尝试将配置中的编码器名称转换为 jtokkit 枚举。
			Optional<EncodingType> encodingTypeOptional = EncodingType
				.fromName(properties.getEmbeddingBatch().getEncodingType());

			// 配置名称无法匹配时使用 OpenAI 常见的 CL100K_BASE。
			encodingType = encodingTypeOptional.orElse(EncodingType.CL100K_BASE);
		}
		catch (Exception e) {
			// 配置解析异常时记录告警并继续使用安全默认值。
			log.warn("Unknown encodingType '{}', falling back to CL100K_BASE",
					properties.getEmbeddingBatch().getEncodingType());
			encodingType = EncodingType.CL100K_BASE;
		}

		// 同时限制 token 数、文本条数和预留比例，避免单次 embedding 请求过大。
		return new EnhancedTokenCountBatchingStrategy(encodingType, properties.getEmbeddingBatch().getMaxTokenCount(),
				properties.getEmbeddingBatch().getReservePercentage(),
				properties.getEmbeddingBatch().getMaxTextCount());
	}

	@Bean
	public ToolCallbackResolver toolCallbackResolver(GenericApplicationContext context) {
		// 收集普通 ToolCallback，但排除只用于 MCP Server 暴露的工具。
		List<ToolCallback> allFunctionAndToolCallbacks = new ArrayList<>(
				McpServerToolUtil.excludeMcpServerTool(context, ToolCallback.class));

		// ToolCallbackProvider 可能一次提供多个回调，因此展开后加入同一集合。
		McpServerToolUtil.excludeMcpServerTool(context, ToolCallbackProvider.class)
			.stream()
			.map(pr -> List.of(pr.getToolCallbacks()))
			.forEach(allFunctionAndToolCallbacks::addAll);

		// 静态解析器按工具名称从已收集回调中查找。
		var staticToolCallbackResolver = new StaticToolCallbackResolver(allFunctionAndToolCallbacks);

		// Spring Bean 解析器允许按需从容器中解析工具 Bean。
		var springBeanToolCallbackResolver = SpringBeanToolCallbackResolver.builder()
			.applicationContext(context)
			.build();

		// 委托解析器按顺序尝试静态集合和 Spring 容器两种来源。
		return new DelegatingToolCallbackResolver(List.of(staticToolCallbackResolver, springBeanToolCallbackResolver));
	}

	/**
	 * 动态生成 EmbeddingModel 的代理 Bean。 原理： 1. 这是一个 Bean，Milvus/PgVector Starter 能看到它，启动不会报错。
	 * 2. 它是动态代理，内部没有写死任何方法。 3. 每次被调用时，它会执行 getTarget() -> registry.getEmbeddingModel()。
	 */
	@Bean
	@Primary
	public EmbeddingModel embeddingModel(AiModelRegistry registry) {

		// 定义动态目标源；代理对象每次收到方法调用都会向它请求真实模型。
		TargetSource targetSource = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				// 告诉 Spring AOP 目标对象实现 EmbeddingModel 接口。
				return EmbeddingModel.class;
			}

			@Override
			public boolean isStatic() {
				// 关键：声明是动态的，每次都要重新获取目标
				return false;
			}

			@Override
			public Object getTarget() {
				// 每次方法调用，都去注册表拿最新的
				return registry.getEmbeddingModel();
			}

			@Override
			public void releaseTarget(Object target) {
				// 无需释放
			}
		};

		// 创建 AOP 代理工厂并绑定上面的动态目标源。
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetSource(targetSource);

		// 使用接口代理，使调用方看到的类型仍然是 EmbeddingModel。
		proxyFactory.addInterface(EmbeddingModel.class);

		// 返回一个稳定的 Spring Bean 引用，实际模型可以在 Registry 中动态切换。
		return (EmbeddingModel) proxyFactory.getProxy();
	}

	@Bean(name = "dbOperationExecutor")
	public ExecutorService dbOperationExecutor() {
		// 初始化专用线程池，用于数据库操作
		// 线程数量设置为CPU核心数的2倍，但不少于4个，不超过16个
		int corePoolSize = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors() * 2, 16));
		log.info("Database operation executor initialized with {} threads", corePoolSize);

		// 自定义线程工厂
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "db-operation-" + threadNumber.getAndIncrement());
				t.setDaemon(false);
				if (t.getPriority() != Thread.NORM_PRIORITY) {
					t.setPriority(Thread.NORM_PRIORITY);
				}
				return t;
			}
		};

		// 创建原生线程池
		this.dbOperationExecutor = new ThreadPoolExecutor(corePoolSize, corePoolSize, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(500), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());

		return dbOperationExecutor;
	}

	@Override
	public void destroy() {
		if (dbOperationExecutor != null && !dbOperationExecutor.isShutdown()) {
			log.info("Shutting down database operation executor...");

			// 记录关闭前的状态，便于排查问题
			if (dbOperationExecutor instanceof ThreadPoolExecutor tpe) {
				log.info("Executor Status before shutdown: [Queue Size: {}], [Active Count: {}], [Completed Tasks: {}]",
						tpe.getQueue().size(), tpe.getActiveCount(), tpe.getCompletedTaskCount());
			}

			// 1. 停止接收新任务
			dbOperationExecutor.shutdown();

			try {
				// 2. 等待现有任务完成（包括队列中的）
				if (!dbOperationExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
					log.warn("Executor did not terminate in 60s. Forcing shutdown...");

					// 3. 超时强行关闭
					dbOperationExecutor.shutdownNow();

					// 4. 再次确认是否关闭
					if (!dbOperationExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
						log.error("Executor failed to terminate completely.");
					}
				}
				else {
					log.info("Database operation executor terminated gracefully.");
				}
			}
			catch (InterruptedException e) {
				log.warn("Interrupted during executor shutdown. Forcing immediate shutdown.");
				dbOperationExecutor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	@Bean(name = "token")
	public TextSplitter textSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.TokenTextSplitterConfig config = textSplitterProps.getToken();
		return new TokenTextSplitter(textSplitterProps.getChunkSize(), config.getMinChunkSizeChars(),
				config.getMinChunkLengthToEmbed(), config.getMaxNumChunks(), config.isKeepSeparator());
	}

	/**
	 * 递归字符文本分块器
	 * @param properties 分块配置
	 * @return RecursiveCharacterTextSplitter实例
	 */
	@Bean(name = "recursive")
	public TextSplitter recursiveTextSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.RecursiveTextSplitterConfig config = textSplitterProps.getRecursive();
		// RecursiveCharacterTextSplitter
		String[] separators = config.getSeparators();
		if (separators != null && separators.length > 0) {
			return new RecursiveCharacterTextSplitter(textSplitterProps.getChunkSize(), separators);
		}
		else {
			return new RecursiveCharacterTextSplitter(textSplitterProps.getChunkSize());
		}
	}

	/**
	 * 句子分块器
	 * @param properties 分块配置
	 * @return SentenceSplitter实例
	 */
	@Bean(name = "sentence")
	public TextSplitter sentenceSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterConfig = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.SentenceTextSplitterConfig sentenceConfig = textSplitterConfig.getSentence();

		return SentenceSplitter.builder()
			.withChunkSize(textSplitterConfig.getChunkSize())
			.withSentenceOverlap(sentenceConfig.getSentenceOverlap())
			.build();
	}

	/**
	 * 语义分块器
	 * @param properties 分块配置
	 * @param embeddingModel Embedding 模型
	 * @return SemanticTextSplitter实例
	 */
	@Bean(name = "semantic")
	public TextSplitter semanticSplitter(DataAgentProperties properties, EmbeddingModel embeddingModel) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.SemanticTextSplitterConfig config = textSplitterProps.getSemantic();
		return SemanticTextSplitter.builder()
			.embeddingModel(embeddingModel)
			.minChunkSize(config.getMinChunkSize())
			.maxChunkSize(config.getMaxChunkSize())
			.similarityThreshold(config.getSimilarityThreshold())
			.build();
	}

	/**
	 * 段落分块器
	 * @param properties 分块配置
	 * @return ParagraphTextSplitter实例
	 */
	@Bean(name = "paragraph")
	public TextSplitter paragraphSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.ParagraphTextSplitterConfig config = textSplitterProps.getParagraph();
		return ParagraphTextSplitter.builder()
			.chunkSize(textSplitterProps.getChunkSize())
			.paragraphOverlapChars(config.getParagraphOverlapChars())
			.build();
	}

}
