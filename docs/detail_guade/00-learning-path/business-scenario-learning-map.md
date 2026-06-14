# 按业务场景分类学习地图

如果你不是按文件读，而是按“我现在只想弄懂某个能力”来读，这份地图更适合你。

## 1. 执行主链

先看：

- [总教程](../01-overview/total-tutorial.md)
- [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)
- [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- [PlannerNode、PlanExecutorNode 与执行控制](../05-method-walkthrough/planner-plan-executor-methods.md)

重点源码：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)
- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)



## 2. RAG 与知识增强

先看：

- [RAG / VectorStore / TextSplitter 深挖](../03-deep-dives/rag-vectorstore-textsplitter-deep-dive.md)
- [IntentRecognition、RAG、Schema、SQL 主链](../05-method-walkthrough/rag-schema-sql-methods.md)

重点源码：

- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [AgentVectorStoreServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java)
- [TextSplitterFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/knowledge/TextSplitterFactory.java)

## 3. NL2SQL

先看：

- [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)
- [IntentRecognition、RAG、Schema、SQL 主链](../05-method-walkthrough/rag-schema-sql-methods.md)
- [Structured Output 深挖](../03-deep-dives/structured-output-by-code.md)

重点源码：

- [SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)
- [TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)
- [SqlGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)
- [SqlExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java)
- [SemanticConsistencyNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SemanticConsistencyNode.java)

## 4. SQL / Python / Report 三段式执行

先看：

- [PlannerNode、PlanExecutorNode 与执行控制](../05-method-walkthrough/planner-plan-executor-methods.md)
- [Python 生成、执行、分析与报告链路](../05-method-walkthrough/python-report-methods.md)

重点源码：

- [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)
- [PythonGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonGenerateNode.java)
- [PythonExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonExecuteNode.java)
- [PythonAnalyzeNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonAnalyzeNode.java)
- [ReportGeneratorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReportGeneratorNode.java)

## 5. 流式输出

先看：

- [Flux / SSE / StreamingOutput 深挖](../03-deep-dives/flux-sse-streamingoutput-deep-dive.md)
- [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)

重点源码：

- [FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)
- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)
- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

## 6. 模型配置与动态切换

先看：

- [模型注册与动态模型切换深挖](../03-deep-dives/model-registry-dynamic-switching-deep-dive.md)
- [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)

重点源码：

- [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
- [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- [ModelConfigController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/ModelConfigController.java)

## 7. MCP / Tool

先看：

- [MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖](../03-deep-dives/mcp-tool-multiturn-human-feedback-deep-dive.md)
- [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)

重点源码：

- [McpServerConfig](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java)
- [McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)
- [McpServerToolUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/McpServerToolUtil.java)

## 8. 多轮对话与人工反馈恢复

先看：

- [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- [MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖](../03-deep-dives/mcp-tool-multiturn-human-feedback-deep-dive.md)

重点源码：

- [GraphServiceImpl.handleNewProcess(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [GraphServiceImpl.handleHumanFeedback(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [MultiTurnContextManager](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java)
- [HumanFeedbackNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/HumanFeedbackNode.java)

## 详细调用地图

| 场景 | 入口 | 核心调用顺序 | 结束位置 |
|---|---|---|---|
| 页面流式分析 | `GraphController.streamSearch` | `graphStreamProcess -> handleNewProcess -> compiledGraph.stream -> subscribeToFlux` | `handleStreamComplete` |
| 只生成 SQL | `McpServerService.nl2SqlToolCallback` | `GraphService.nl2sql -> CompiledGraph.invoke` | 返回 `SQL_GENERATE_OUTPUT` |
| 业务知识召回 | `EvidenceRecallNode.apply` | query rewrite -> 两类向量检索 -> 证据拼装 | 写入 `EVIDENCE` |
| Schema 召回 | `SchemaRecallNode.apply` | 表召回 -> 字段召回 -> `TableRelationNode` 精筛 | 写入 `TABLE_RELATION_OUTPUT` |
| SQL 执行 | `SqlGenerateNode` | 生成 -> 语义校验 -> 执行 -> dispatcher | 回计划执行器或重生成 |
| Python 分析 | `PythonGenerateNode` | 代码生成 -> 沙箱执行 -> 结果分析 | 当前计划步 `+1` |
| 报告生成 | `ReportGeneratorNode.apply` | 读取计划与步骤结果 -> LLM 汇总 | 写入 `RESULT` |
| 人工恢复 | `GraphServiceImpl.handleHumanFeedback` | `updateState -> stream(null, resumeConfig)` | 回 HumanFeedback 路由 |

### 场景之间怎样衔接

业务问题不会直接进入 SQL。它先经过意图识别、证据召回、查询增强、Schema 召回和可行性判断，再由 Planner 生成执行计划。计划中的每个 `ExecutionStep` 决定进入 SQL、Python 或报告节点，完成一个步骤后通过 `PLAN_CURRENT_STEP + 1` 回到 `PlanExecutorNode`。

因此项目不是几条互不相关的能力，而是一个状态驱动循环：

```text
语义准备
  -> 生成计划
  -> 校验当前步骤
  -> 执行 SQL/Python
  -> 保存步骤结果
  -> currentStep + 1
  -> 再次校验下一步骤
  -> 生成报告或结束
```
