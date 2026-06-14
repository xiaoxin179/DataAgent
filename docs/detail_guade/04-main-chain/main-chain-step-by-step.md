# 主链路逐步执行图解

这篇只做一件事：把一次典型请求从进入后端到生成结果，按顺序串清楚。

## 1. 请求从哪里进

入口在 [GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)。

它接收的关键信息有：

- `agentId`
- `threadId`
- `query`
- `humanFeedback`
- `humanFeedbackContent`
- `rejectedPlan`
- `nl2sqlOnly`

第一步不是跑模型，而是先建立 SSE 通道。

## 2. GraphServiceImpl 先分成两条路

看 [GraphServiceImpl.graphStreamProcess(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)。

它会先判断：

- 如果带了 `humanFeedbackContent`，走 `handleHumanFeedback(...)`
- 否则走 `handleNewProcess(...)`

所以这个仓库天然支持“新请求”和“恢复请求”两种执行路径。

## 3. 新请求路径怎么开始

`handleNewProcess(...)` 会做 4 件事：

1. 用 `threadId` 建或复用 `StreamContext`
2. 用 [MultiTurnContextManager](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java) 拼好多轮上下文
3. 调 `compiledGraph.stream(...)`
4. 把图输出交给 `subscribeToFlux(...)`

到这里，请求就从 HTTP 世界正式进入 Graph 世界了。

## 4. 图里的典型执行顺序

由 [DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java) 定义。

典型顺序是：

1. [IntentRecognitionNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)
2. [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
3. [QueryEnhanceNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/QueryEnhanceNode.java)
4. [SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)
5. [TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)
6. [FeasibilityAssessmentNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/FeasibilityAssessmentNode.java)
7. [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
8. [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)

从这里开始才进入 SQL / Python / Report 分支。

## 5. 计划分支怎么推进

### `PlannerNode`

负责生成结构化 `Plan`。

### `PlanExecutorNode`

负责：

- 校验计划
- 决定当前步骤下一跳
- 必要时跳去 `HUMAN_FEEDBACK_NODE`

### `PlanExecutorDispatcher`

负责解释状态，决定：

- 回 `PlannerNode` 修计划
- 去 `SQL_GENERATE_NODE`
- 去 `PYTHON_GENERATE_NODE`
- 去 `REPORT_GENERATOR_NODE`

## 6. SQL 分支怎么跑

顺序通常是：

1. [SqlGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)
2. [SemanticConsistencyNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SemanticConsistencyNode.java)
3. [SqlExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java)
4. [SQLExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SQLExecutorDispatcher.java) 决定回退还是继续

关键点：

- SQL 不是生成完就执行
- 先过语义一致性校验
- 执行失败还能带错误信息回 `SqlGenerateNode` 修复

## 7. Python 分支怎么跑

顺序通常是：

1. [PythonGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonGenerateNode.java)
2. [PythonExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonExecuteNode.java)
3. [PythonAnalyzeNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonAnalyzeNode.java)

关键点：

- Python 用于 SQL 结果的二次处理
- 执行失败可以重试
- 超过最大重试次数还能走 fallback 模式

## 8. 报告分支怎么收尾

最后由 [ReportGeneratorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReportGeneratorNode.java)：

- 重新读取 Planner 输出
- 汇总每一步 SQL / Python 结果
- 结合 `summaryAndRecommendations`
- 生成最终 Markdown 报告

## 9. 流式输出怎么一路回到前端

执行过程中：

1. node 用 [FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java) 把模型流包装成节点输出流
2. [GraphServiceImpl.handleNodeOutput(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java) 读取节点输出
3. 再转成 `GraphNodeResponse`
4. 最后通过 `Sinks.Many` 发成 SSE

## 10. 人工反馈恢复怎么接回主链

当图在 `HUMAN_FEEDBACK_NODE` 前停住后：

1. 前端带着 `threadId` 和反馈再次请求
2. `GraphServiceImpl.handleHumanFeedback(...)` 调 `compiledGraph.updateState(...)`
3. 再 `compiledGraph.stream(null, resumeConfig)` 继续

这不是重跑，而是续跑。

## 11. 建议连读

- [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- [PlannerNode、PlanExecutorNode 与执行控制](../05-method-walkthrough/planner-plan-executor-methods.md)
- [IntentRecognition、RAG、Schema、SQL 主链](../05-method-walkthrough/rag-schema-sql-methods.md)

## 12. 主链精确调用顺序

```text
START
 -> IntentRecognitionNode
 -> IntentRecognitionDispatcher
    -> 无关问题: END
    -> 数据问题: EvidenceRecallNode
 -> QueryEnhanceNode
 -> QueryEnhanceDispatcher
 -> SchemaRecallNode
 -> SchemaRecallDispatcher
 -> TableRelationNode
 -> TableRelationDispatcher
    -> 可重试错误: TableRelationNode
    -> 成功: FeasibilityAssessmentNode
 -> FeasibilityAssessmentDispatcher
 -> PlannerNode
 -> PlanExecutorNode
 -> PlanExecutorDispatcher
    -> SQL_GENERATE_NODE
    -> PYTHON_GENERATE_NODE
    -> REPORT_GENERATOR_NODE
    -> HUMAN_FEEDBACK_NODE
    -> PLANNER_NODE
    -> END
```

SQL 子循环：

```text
SqlGenerateNode
 -> SqlGenerateDispatcher
 -> SemanticConsistencyNode
 -> SemanticConsistenceDispatcher
    -> 不一致: SqlGenerateNode
    -> 一致: SqlExecuteNode
 -> SQLExecutorDispatcher
    -> 执行失败: SqlGenerateNode
    -> 成功: PlanExecutorNode
```

Python 子循环：

```text
PythonGenerateNode
 -> PythonExecuteNode
 -> PythonExecutorDispatcher
    -> 未达重试上限: PythonGenerateNode
    -> 成功/fallback: PythonAnalyzeNode
    -> 不可恢复: END
 -> PlanExecutorNode
```

### 控制流与数据流是两条线

控制流由 edge/dispatcher 决定，数据流由 `OverAllState` 承载。比如 `SqlExecuteNode` 成功后不会直接调用 `PlanExecutorNode`；它写入执行结果和新的步号，随后 `SQLExecutorDispatcher` 返回 `PLAN_EXECUTOR_NODE`，Graph 框架才调用下一个节点。

这一区分非常关键：

- Node 负责“做事并写状态”。
- Dispatcher 负责“根据状态选路”。
- StateGraph 负责“真正调度下一节点”。
