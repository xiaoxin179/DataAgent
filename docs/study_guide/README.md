# DataAgent 后端源码学习文档体系

这套资料的目标不是“介绍项目”，而是带你顺着真实源码，把 DataAgent 的后端主链、Spring AI 落地方式、Graph 编排、RAG、流式输出、MCP、动态模型切换这些能力真正读明白。

如果你只想先抓住主链，先看这 3 篇：

1. [总教程：先建立全局认知](./01-overview/total-tutorial.md)
2. [主链路逐步执行图解](./04-main-chain/main-chain-step-by-step.md)
3. [请求入口与 GraphServiceImpl 逐段讲解](./05-method-walkthrough/request-entry-graph-service-methods.md)

## 第一层：总教程

- [总教程：项目定位、技术栈、模块与主链](./01-overview/total-tutorial.md)
- [按源码拆架构：配置、模块、状态流、设计取舍](./01-overview/backend-architecture-by-code.md)

## 第二层：框架 API 代码级总览

- [框架 API 代码级讲解总览](./02-api-code-guide/framework-api-code-level-guide.md)
- [Spring Boot SSE 后端实战教程](./02-api-code-guide/spring-boot-sse-backend-tutorial.md)

这篇会重点讲：

- `ChatClient` / `ChatModel` / `EmbeddingModel`
- `PromptTemplate` / `BeanOutputConverter` / `JsonParseUtil`
- `VectorStore` / `TextSplitter`
- `StateGraph` / `CompiledGraph` / `OverAllState` / `KeyStrategyFactory`
- `Flux` / `SSE`
- `@Tool` / `ToolCallbackProvider`
- `RestClientCustomizer` / `WebClient.Builder`

## 第三层：专题深挖

- [ChatClient / ChatModel / EmbeddingModel 深挖](./03-deep-dives/chatclient-chatmodel-embeddingmodel-deep-dive.md)
- [Structured Output 深挖](./03-deep-dives/structured-output-by-code.md)
- [StateGraph / CompiledGraph / OverAllState 深挖](./03-deep-dives/stategraph-compiledgraph-overallstate-deep-dive.md)
- [Flux / SSE / StreamingOutput 深挖](./03-deep-dives/flux-sse-streamingoutput-deep-dive.md)
- [RAG / VectorStore / TextSplitter 深挖](./03-deep-dives/rag-vectorstore-textsplitter-deep-dive.md)
- [模型注册与动态模型切换深挖](./03-deep-dives/model-registry-dynamic-switching-deep-dive.md)
- [MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖](./03-deep-dives/mcp-tool-multiturn-human-feedback-deep-dive.md)

## 第四层：关键方法逐段讲解

- [请求入口、Graph 启动、人工反馈恢复](./05-method-walkthrough/request-entry-graph-service-methods.md)
- [PlannerNode、PlanExecutorNode 与执行控制](./05-method-walkthrough/planner-plan-executor-methods.md)
- [IntentRecognition、RAG、Schema、SQL 双阶段主链](./05-method-walkthrough/rag-schema-sql-methods.md)
- [Python 生成、执行、分析与报告链路](./05-method-walkthrough/python-report-methods.md)
- [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](./05-method-walkthrough/utility-registry-mcp-methods.md)

## 第五层：阅读顺序与学习地图

- [建议阅读顺序总索引](./00-learning-path/reading-order-guide.md)
- [按业务场景分类学习地图](./00-learning-path/business-scenario-learning-map.md)

## 第六层：主链导航

- [主链路逐步执行图解](./04-main-chain/main-chain-step-by-step.md)
- [源码入口地图：从哪里开始读](./04-main-chain/backend-source-entry-map.md)

## 第七层：亮点专题

- [仓库亮点与面试讲法总结](./06-highlights/interview-highlights-by-repo.md)

## 可搭配阅读的仓库原文档

- [官方架构文档](../ARCHITECTURE.md)
- [开发指南](../DEVELOPER_GUIDE.md)
- [高级特性](../ADVANCED_FEATURES.md)
- [知识库说明](../KNOWLEDGE_USAGE.md)
- [快速开始](../QUICK_START.md)

## 说明

- 这套资料优先聚焦 `data-agent-management` 后端。
- 文档中的源码引用全部使用相对路径。
- 本目录已经收敛为当前唯一推荐入口，阅读时直接从本页展开即可。
