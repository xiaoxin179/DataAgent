# 建议阅读顺序总索引

这份索引不按包名排，而按“你此刻最想解决什么问题”来排。

## 第 0 阶段：先建立全局图

先看：

1. [总教程](../01-overview/total-tutorial.md)
2. [按源码拆架构](../01-overview/backend-architecture-by-code.md)
3. [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)

这一阶段的目标只有一个：知道请求从哪里进、图从哪里起、状态怎么流、SQL/Python/报告三条链怎么分。

## 第 1 阶段：把主链读通

按这个顺序看：

1. [源码入口地图](../04-main-chain/backend-source-entry-map.md)
2. [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
3. [PlannerNode、PlanExecutorNode 与执行控制](../05-method-walkthrough/planner-plan-executor-methods.md)
4. [IntentRecognition、RAG、Schema、SQL 主链](../05-method-walkthrough/rag-schema-sql-methods.md)
5. [Python 与报告链](../05-method-walkthrough/python-report-methods.md)

这一阶段读完，你应该能口头讲清楚：

- `GraphController -> GraphServiceImpl -> CompiledGraph.stream(...)` 怎么串起来
- 为什么 `PlannerNode` 只负责出计划，`PlanExecutorNode` 才负责推进计划
- 为什么 SQL 不是“生成完就执行”，中间还要过 `SemanticConsistencyNode`

## 第 2 阶段：系统学框架 API

先看：

1. [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)

再按兴趣专项补：

1. [ChatClient / ChatModel / EmbeddingModel 深挖](../03-deep-dives/chatclient-chatmodel-embeddingmodel-deep-dive.md)
2. [Structured Output 深挖](../03-deep-dives/structured-output-by-code.md)
3. [StateGraph 深挖](../03-deep-dives/stategraph-compiledgraph-overallstate-deep-dive.md)
4. [Flux / SSE 深挖](../03-deep-dives/flux-sse-streamingoutput-deep-dive.md)
5. [RAG / VectorStore 深挖](../03-deep-dives/rag-vectorstore-textsplitter-deep-dive.md)
6. [模型注册与动态模型切换深挖](../03-deep-dives/model-registry-dynamic-switching-deep-dive.md)
7. [MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖](../03-deep-dives/mcp-tool-multiturn-human-feedback-deep-dive.md)

## 如果你只想快速理解后端主链

只看这 5 篇就够：

1. [总教程](../01-overview/total-tutorial.md)
2. [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)
3. [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
4. [PlannerNode、PlanExecutorNode 与执行控制](../05-method-walkthrough/planner-plan-executor-methods.md)
5. [IntentRecognition、RAG、Schema、SQL 主链](../05-method-walkthrough/rag-schema-sql-methods.md)

## 如果你只想系统学完整仓库

建议顺序：

1. [总教程](../01-overview/total-tutorial.md)
2. [按源码拆架构](../01-overview/backend-architecture-by-code.md)
3. [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)
4. [全部专题深挖](../03-deep-dives/chatclient-chatmodel-embeddingmodel-deep-dive.md)
5. [全部关键方法讲解](../05-method-walkthrough/request-entry-graph-service-methods.md)
6. [亮点与面试讲法](../06-highlights/interview-highlights-by-repo.md)

## 如果你只想学框架 API

优先看：

1. [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)
2. [Structured Output 深挖](../03-deep-dives/structured-output-by-code.md)
3. [StateGraph 深挖](../03-deep-dives/stategraph-compiledgraph-overallstate-deep-dive.md)
4. [Flux / SSE 深挖](../03-deep-dives/flux-sse-streamingoutput-deep-dive.md)
5. [模型注册与动态模型切换深挖](../03-deep-dives/model-registry-dynamic-switching-deep-dive.md)

## 如果你想拿这个项目去讲亮点

建议顺序：

1. [总教程](../01-overview/total-tutorial.md)
2. [StateGraph 深挖](../03-deep-dives/stategraph-compiledgraph-overallstate-deep-dive.md)
3. [MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖](../03-deep-dives/mcp-tool-multiturn-human-feedback-deep-dive.md)
4. [仓库亮点与面试讲法总结](../06-highlights/interview-highlights-by-repo.md)

## 详细版学习节奏

### 第一遍：只追控制流

先不研究 prompt 内容和 DTO 字段，只回答：

```text
请求从哪进 -> 图从哪启动 -> 当前节点是谁 -> dispatcher 跳到哪 -> 最后怎样结束
```

建议在这些位置打断点：

1. `GraphController.streamSearch(...)`
2. `GraphServiceImpl.graphStreamProcess(...)`
3. `GraphServiceImpl.handleNewProcess(...)`
4. `IntentRecognitionNode.apply(...)`
5. `PlanExecutorNode.apply(...)`
6. `GraphServiceImpl.handleStreamNodeOutput(...)`
7. `GraphServiceImpl.handleStreamComplete(...)`

### 第二遍：追 state

每进入一个节点就观察 `OverAllState`，记录“读 key”和“写 key”。最关键的状态组是：

| 状态组 | 关键 key |
|---|---|
| 请求身份 | `INPUT_KEY`、`AGENT_ID`、`TRACE_THREAD_ID` |
| 语义增强 | `INTENT_RECOGNITION_NODE_OUTPUT`、`EVIDENCE`、`QUERY_ENHANCE_NODE_OUTPUT` |
| Schema | `TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT`、`TABLE_RELATION_OUTPUT`、`DB_DIALECT_TYPE` |
| 计划 | `PLANNER_NODE_OUTPUT`、`PLAN_CURRENT_STEP`、`PLAN_NEXT_NODE` |
| SQL | `SQL_GENERATE_OUTPUT`、`SQL_REGENERATE_REASON`、`SQL_EXECUTE_NODE_OUTPUT` |
| Python | `PYTHON_GENERATE_NODE_OUTPUT`、`PYTHON_EXECUTE_NODE_OUTPUT`、`PYTHON_IS_SUCCESS` |
| 人工反馈 | `HUMAN_REVIEW_ENABLED`、`HUMAN_FEEDBACK_DATA` |
| 最终结果 | `RESULT` |

### 第三遍：追三层流

分别观察：

1. `LlmService` 如何产生模型流。
2. `FluxUtil` 如何把模型流变成节点流，并在结束时写回 state。
3. `GraphServiceImpl` 如何把图输出变成 SSE。

不要把 `subscribe()` 当成简单的集合遍历。它是启动消费并注册 `onNext`、`onError`、`onComplete` 三个回调。

### 第四遍：追失败路径

成功链只说明“能跑”；失败链才说明“工程上是否完整”。依次验证：

- SQL 语义校验失败如何回到 `SqlGenerateNode`
- SQL 执行失败如何携带 `SqlRetryDto`
- Python 执行失败如何重试或进入 fallback
- 用户关闭浏览器如何触发 `Disposable.dispose()`
- 人工拒绝计划如何回到 Planner 修复
