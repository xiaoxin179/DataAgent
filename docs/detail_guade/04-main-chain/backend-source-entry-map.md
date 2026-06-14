# 源码入口地图：从哪里开始读最不容易迷路

如果你直接从 `workflow/node` 往里扎，很容易只看见局部。更好的顺序是先抓入口，再抓骨架，再抓关键节点。

## 1. 第一个入口：HTTP + SSE

先看：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

重点方法：

- `streamSearch(...)`

你要回答：

- 请求参数有哪些
- 为什么返回 `Flux<ServerSentEvent<GraphNodeResponse>>`
- 为什么 controller 自己不跑 AI 逻辑

## 2. 第二个入口：Graph 执行协调层

接着看：

- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

重点方法：

- `graphStreamProcess(...)`
- `handleNewProcess(...)`
- `handleHumanFeedback(...)`
- `subscribeToFlux(...)`

你要回答：

- `threadId` 为何这么重要
- `CompiledGraph` 是怎么被调用的
- 人工反馈恢复为什么不重跑全链

## 3. 第三个入口：图定义层

再看：

- [DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

重点方法：

- `nl2sqlGraph(...)`
- `toolCallbackResolver(...)`
- `embeddingModel(...)`
- `simpleVectorStore(...)`

你要回答：

- 节点顺序怎么定义
- 分支条件怎么定义
- `KeyStrategyFactory` 怎么定义 state 风格

## 4. 第四个入口：模型与工具基础设施

看：

- [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
- [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- [McpServerConfig](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java)
- [McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)

这是“模型怎么来、工具怎么来”的入口。

## 5. 第五个入口：计划与执行控制

看：

- [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
- [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)
- [PlanExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/PlanExecutorDispatcher.java)

这是最能体现设计思想的一组类。

## 6. 第六个入口：RAG 与 SQL 主链

看：

- [IntentRecognitionNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)
- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)
- [TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)
- [SqlGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)
- [SqlExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java)

## 7. 第七个入口：Python 与报告收尾

看：

- [PythonGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonGenerateNode.java)
- [PythonExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonExecuteNode.java)
- [PythonAnalyzeNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonAnalyzeNode.java)
- [ReportGeneratorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReportGeneratorNode.java)

## 8. 第八个入口：工具类与稳定性底座

最后看：

- [FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)
- [JsonParseUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/JsonParseUtil.java)
- [PromptHelper](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java)

这几个类最能解释“为什么系统不是跑通就算，而是尽量做稳”。

## 9. 一句话阅读策略

最推荐的顺序是：

`Controller -> GraphServiceImpl -> DataAgentConfiguration -> Planner/Executor -> RAG/SQL -> Python/Report -> Util`

## 10. 建议连读

- [总教程](../01-overview/total-tutorial.md)
- [按源码拆架构](../01-overview/backend-architecture-by-code.md)
- [建议阅读顺序总索引](../00-learning-path/reading-order-guide.md)

## 11. 按断点进入源码

| 断点 | 观察内容 | 下一步进入 |
|---|---|---|
| `GraphController.streamSearch` | HTTP 参数、sink 类型、返回 Flux | `graphStreamProcess` |
| `GraphServiceImpl.handleNewProcess` | 初始 state Map、threadId | `CompiledGraph.stream` |
| 任一 `NodeAction.apply` | state 当前值、节点依赖 | `FluxUtil` 或业务 Service |
| 任一 `Dispatcher.apply` | 分支条件、返回的节点名 | 下一 Node |
| `handleStreamNodeOutput` | node、chunk、TextType | `sink.tryEmitNext` |
| `handleStreamComplete` | 历史、span、sink、cleanup | Controller `doOnComplete` |

阅读某个节点时，按这个固定模板做笔记：

```text
节点名：
谁把控制权交给它：
读取的 state key：
调用的 Service：
产生的流或同步结果：
写回的 state key：
哪个 dispatcher 读取这些结果：
成功下一跳：
失败/重试下一跳：
```
