# 总教程：先把 DataAgent 后端读成一张图

这篇先不钻某个类，而是先回答 5 个问题：

1. 这个仓库到底在做什么
2. 主链从哪里启动
3. 为什么要用 Graph 编排，而不是几个 Service 直接串
4. Spring AI / Spring AI Alibaba / Reactor / SSE / RAG / MCP 分别落在哪
5. 先读哪些源码，最容易建立全局认知

## 1. 项目定位

DataAgent 不是一个“聊天接口套大模型”的项目，它更像一个面向数据分析场景的后端执行引擎。

它想解决的是这条长链：

1. 用户提一个自然语言问题
2. 系统先判断这是不是数据分析问题
3. 再去补业务知识、补 Schema、补表关系
4. 然后让模型先做**计划**，而不是直接下 SQL
5. 计划执行时再决定当前步骤走 SQL、Python 还是报告
6. 中间全程流式输出
7. 允许人工在计划阶段中断、反馈、恢复继续跑

这也是为什么它的核心不是某一个 Prompt，而是 [DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java) 里定义出来的 `StateGraph`。

## 2. 你要先抓住的 3 条主线

### 2.1 请求主线

请求入口是 [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)。

关键方法：

- `streamSearch(...)`

它不做 AI 推理，只做三件事：

- 接 HTTP 参数
- 建 SSE 通道
- 把执行权交给 [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

### 2.2 图执行主线

真正的执行入口在 [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)。

关键方法：

- `graphStreamProcess(...)`
- `handleNewProcess(...)`
- `handleHumanFeedback(...)`

这里负责把“HTTP 请求”转成“Graph 运行”，并把 `threadId`、多轮上下文、人工反馈恢复、SSE 生命周期都接起来。

### 2.3 工作流主线

图定义在 [DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)。

主节点顺序大致是：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `QueryEnhanceNode`
4. `SchemaRecallNode`
5. `TableRelationNode`
6. `FeasibilityAssessmentNode`
7. `PlannerNode`
8. `PlanExecutorNode`
9. `SqlGenerateNode` / `PythonGenerateNode` / `ReportGeneratorNode`

你可以把它理解成：先理解问题，再准备上下文，再出计划，再执行计划。
![](../05-method-walkthrough/nodel.flow_chart.png)

## 3. 为什么这里一定要先 Planner，再 PlanExecutor

这是整个仓库最值得学的设计点。

### 如果没有 Planner / PlanExecutor 分层

常见写法是：

- 直接让模型生成 SQL
- 或者直接让模型自己决定接下来做什么

问题是：

- 计划不可见
- 人工无法在计划层介入
- 一旦执行失败，很难只修复局部步骤
- SQL、Python、报告三条链会混成一团

### 这个仓库的拆法

- [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java) 只负责产出结构化计划
- [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java) 只负责校验计划、推进当前步骤、决定下一个节点
- 各个 dispatcher 再负责跳转规则

这就把三件事拆开了：

1. 计划生成
2. 计划推进
3. 节点跳转

你后面读源码时，脑子里一定要一直保留这个分层。

## 4. 主要框架知识点在仓库里的真实落点

### Spring Boot

看这里：

- [data-agent-management/pom.xml](../../../data-agent-management/pom.xml)
- [application.yml](../../../data-agent-management/src/main/resources/application.yml)
- [DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

它负责把模型、向量库、TextSplitter、Graph、HTTP Client、Tool 解析器这些基础设施装起来。

### Spring AI

看这里：

- [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
- [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- [StreamLlmService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)

落点主要是：

- `ChatClient`
- `ChatModel`
- `EmbeddingModel`
- `PromptTemplate`
- `BeanOutputConverter`
- `VectorStore`
- `TextSplitter`

### Spring AI Alibaba Graph

看这里：

- [DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

落点主要是：

- `StateGraph`
- `CompiledGraph`
- `OverAllState`
- `KeyStrategyFactory`

### Reactor / SSE

看这里：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)
- [FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)
- [GraphServiceImpl.handleStreamNodeOutput(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

这里至少有 3 层流：

1. 模型流：`Flux<ChatResponse>`
2. 节点流：`Flux<NodeOutput>` 或 `Flux<GraphResponse<StreamingOutput>>`
3. SSE 流：`Flux<ServerSentEvent<GraphNodeResponse>>`

### RAG / VectorStore

看这里：

- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)
- [AgentVectorStoreServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java)

注意这个仓库把“知识入库”和“知识召回”拆在不同层：

- 入库更偏 `service/knowledge`、`service/vectorstore`
- 召回更偏 workflow node

### MCP / Tool

看这里：

- [McpServerConfig](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java)
- [McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)
- [McpServerToolUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/McpServerToolUtil.java)

## 5. 主链为什么这样设计

### 不是“一次性直接生成答案”，而是“阶段化编排”

因为数据分析任务本身就分阶段：

- 理解问题
- 找上下文
- 找表
- 出计划
- 执行
- 汇总

### 不是“只有 SQL”，而是“SQL + Python + Report”

因为很多分析问题：

- SQL 负责取数
- Python 负责二次计算和程序式分析
- 报告负责把中间结果翻译成用户能读的结论

### 不是“一跑到底”，而是“支持人工中断恢复”

看 [GraphServiceImpl.handleHumanFeedback(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java) 和 [HumanFeedbackNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/HumanFeedbackNode.java)。

这说明项目设计目标不是 Demo，而是更接近企业场景里的可控执行。

## 6. 最推荐的源码入口

如果你现在准备开始读代码，优先级是：

1. [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)
2. [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
3. [DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
4. [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
5. [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)
6. [SqlGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)
7. [SqlExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java)
8. [PythonGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonGenerateNode.java)
9. [PythonExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonExecuteNode.java)
10. [ReportGeneratorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReportGeneratorNode.java)

## 7. 建议连读

- 下一篇先看：[按源码拆架构](./backend-architecture-by-code.md)
- 想顺着流程读：看 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)
- 想直接抠方法：看 [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)

## 8. 一次请求的完整时间线

1. Spring 根据 `/api/stream/search` 路由进入 `GraphController.streamSearch(...)`。
2. Controller 设置 SSE 响应头并创建 `unicast` sink。
3. HTTP 参数被封装成 `GraphRequest`。
4. `GraphServiceImpl.graphStreamProcess(...)` 确保存在 `threadId`。
5. `streamContextMap.computeIfAbsent(...)` 创建或复用当前流上下文。
6. 有 `humanFeedbackContent` 时走恢复；否则走新流程。
7. 新流程先读取已完成的多轮历史，再登记本轮 pending turn。
8. `compiledGraph.stream(initialState, runnableConfig)` 启动图。
9. Graph 从 `START` 进入意图识别，之后由固定边或条件边推进。
10. 每个节点从 `OverAllState` 读输入，返回 `Map<String,Object>` 作为状态更新。
11. 流式节点通过 `FluxUtil` 同时完成“向外吐 chunk”和“结束后写最终值”。
12. `subscribeToFlux(...)` 注册数据、异常、完成三个回调。
13. 数据回调把 `StreamingOutput` 包成 `GraphNodeResponse` 并写入 sink。
14. Controller 的 `sink.asFlux()` 把事件交给 WebFlux 写入 HTTP 长连接。
15. 正常完成时保存多轮历史、结束 span、发 complete 事件并清理资源。

## 9. 四个运行时对象不要混

| 对象 | 生命周期 | 保存什么 |
|---|---|---|
| `GraphRequest` | 一次 HTTP 调用 | 请求参数和控制开关 |
| `RunnableConfig` | 一次 Graph 启动/恢复 | `threadId`、metadata、恢复配置 |
| `OverAllState` | 一次 Graph 执行 | 所有节点共享的业务状态 |
| `StreamContext` | 一次 SSE 流任务 | sink、Disposable、span、文本类型、累计输出 |

`threadId` 把这几层串起来，但它们不是同一个对象。理解这一点后，新请求、恢复请求和取消请求的边界会清楚很多。
