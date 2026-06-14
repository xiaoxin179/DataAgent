# StateGraph / CompiledGraph / OverAllState 深挖

如果你读这个仓库时，感觉 node 很多、dispatcher 很多、state key 也很多，看着像一团，那基本不是你 Java 基础的问题，而是还没有先把 Graph 这一层的“运行方式”想明白。

这篇就做一件事：把 `StateGraph`、`CompiledGraph`、`OverAllState` 这三个核心对象讲顺。你把这三个对象看顺了，后面再去看 `PlannerNode`、`SqlGenerateNode`、`HumanFeedbackDispatcher`，会轻很多。

## 先说结论：这三个对象分别管什么

- `StateGraph`
  管定义。也就是这张图长什么样，有哪些节点，谁后面接谁。
- `CompiledGraph`
  管执行。也就是这张图真正怎么跑，跑到哪暂停，怎么恢复。
- `OverAllState`
  管数据流。也就是节点之间到底靠什么传递输入、输出和控制信息。

你可以把它们想成：

- `StateGraph` 是施工图
- `CompiledGraph` 是已经搭好的流水线
- `OverAllState` 是流水线上流动的物料和工单

## 1. 为什么这个项目不是几个 Service 串起来，而是要上 Graph

先别急着看 API，先看这个项目到底在解决什么问题。

DataAgent 不是简单的“自然语言进来，SQL 出去”。它的主链路里至少有这几段：

1. 先识别是不是分析问题
2. 再做业务证据召回
3. 再做 Schema 召回
4. 再拼表关系
5. 再判断这个问题能不能做
6. 再让模型生成计划
7. 再按计划决定是走 SQL、Python 还是报告
8. 中间还能插人工反馈
9. 还得支持流式输出

如果你用几个 `service.xxx()` 顺序串起来，马上会遇到几个问题：

- 哪些地方允许条件跳转
- 哪些地方允许重试
- 哪些地方允许暂停
- 人工反馈回来后从哪继续
- 每个节点的中间结果放哪

所以它最终选的是 Graph，不是为了“高级”，而是因为这个问题本身就已经像工作流了。

图的定义入口在这里：
[DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

## 2. 先看定义阶段：`StateGraph` 到底在定义什么

`StateGraph` 只做一件事：把工作流长什么样定义出来。

这个仓库里真正关键的代码，不是某一行 `new StateGraph(...)`，而是后面这几类操作：

```java
StateGraph stateGraph = new StateGraph(NL2SQL_GRAPH_NAME, keyStrategyFactory)
    .addNode(INTENT_RECOGNITION_NODE, ...)
    .addNode(EVIDENCE_RECALL_NODE, ...)
    .addNode(PLANNER_NODE, ...)
    .addNode(PLAN_EXECUTOR_NODE, ...);

stateGraph.addEdge(START, INTENT_RECOGNITION_NODE)
    .addConditionalEdges(INTENT_RECOGNITION_NODE, ...)
    .addEdge(EVIDENCE_RECALL_NODE, QUERY_ENHANCE_NODE)
    .addConditionalEdges(PLAN_EXECUTOR_NODE, ...)
    .addConditionalEdges(HUMAN_FEEDBACK_NODE, ...);
```

对应源码：
[DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

你读这段代码时，建议不要一上来逐行抠，而是先按下面三个问题去看。

### 2.1 这张图里有哪些业务节点

仓库里的主节点大致是：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `QueryEnhanceNode`
4. `SchemaRecallNode`
5. `TableRelationNode`
6. `FeasibilityAssessmentNode`
7. `PlannerNode`
8. `PlanExecutorNode`
9. `SqlGenerateNode`
10. `SqlExecuteNode`
11. `PythonGenerateNode`
12. `PythonExecuteNode`
13. `PythonAnalyzeNode`
14. `ReportGeneratorNode`
15. `HumanFeedbackNode`

不要把它们看成“15 个杂乱类”，而要看成 4 段：

- 问题理解段
- RAG 和 Schema 准备段
- 计划生成与计划推进段
- SQL / Python / 报告执行段

### 2.2 哪些边是固定边，哪些边是条件边

固定边你可以理解成“这步做完以后，天然就该去下一步”。

比如：

```java
.addEdge(EVIDENCE_RECALL_NODE, QUERY_ENHANCE_NODE)
.addEdge(PLANNER_NODE, PLAN_EXECUTOR_NODE)
.addEdge(PYTHON_GENERATE_NODE, PYTHON_EXECUTE_NODE)
```

条件边则代表“下一步不是写死的，要看当前 state 决定”。

比如：

```java
.addConditionalEdges(PLAN_EXECUTOR_NODE, edge_async(new PlanExecutorDispatcher()), Map.of(...))
.addConditionalEdges(SQL_EXECUTE_NODE, edge_async(new SQLExecutorDispatcher()), Map.of(...))
.addConditionalEdges(HUMAN_FEEDBACK_NODE, edge_async(new HumanFeedbackDispatcher()), Map.of(...))
```

这三个条件边是最值得反复看的，因为它们分别决定：

- 计划下一步到底走 SQL、Python 还是报告
- SQL 执行后要不要回炉重生成
- 人工反馈后是回到 Planner 还是继续执行

### 2.3 为什么 node 和 dispatcher 要拆开

这是这个仓库 Graph 设计里最有味道的地方。

先看一组典型组合：

- [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)
- [PlanExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/PlanExecutorDispatcher.java)

`PlanExecutorNode` 负责什么：

- 把 Planner 产出的计划读出来
- 校验这个计划是否合法
- 判断当前步应该执行什么
- 把 `PLAN_NEXT_NODE`、`PLAN_VALIDATION_STATUS` 这些状态写回去

`PlanExecutorDispatcher` 负责什么：

- 根据 `PLAN_NEXT_NODE` 和其他控制字段
- 决定图下一跳去哪

这个拆法的好处非常实在：

- node 只做业务判断，不关心“图怎么跳”
- dispatcher 只做跳转，不关心“计划怎么校验”
- 一旦以后加新分支，不用把一个类改成一锅粥

你如果只看 node，不看 dispatcher，很容易误以为“node 自己决定下一跳”。其实不是。node 只是把判断结果写进 state，真正跳转发生在 dispatcher。

## 3. `OverAllState` 才是你读源码时最该追的东西

很多人读工作流类项目，习惯追方法调用链。这个习惯在普通 Spring 项目里很好用，但到了这个仓库，只追方法调用链是不够的。

因为这里节点之间的协作，不靠 `A 调 B`，而靠 `A 写 state，B 读 state`。

### 3.1 `OverAllState` 是什么

`OverAllState` 是整条图执行时的共享状态容器。

你可以把它看成一个“有规则的全局 Map”。
所谓“有规则”，不是它真的只有 `Map` 那么简单，而是：

- 节点会从里面读数据
- 节点会把结果写回里面
- 图框架会按 `KeyStrategyFactory` 的规则合并这些结果

### 3.2 这个仓库里 state 怎么读

最常见的两种读法：

第一种，直接读：

```java
Boolean onlyNl2sql = state.value(IS_ONLY_NL2SQL, false);
String semanticModel = (String) state.value(GENEGRATED_SEMANTIC_MODEL_PROMPT).orElse("");
```

真实源码：
[PlannerNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

第二种，走工具类读：

```java
String userInput = StateUtil.getStringValue(state, INPUT_KEY);
SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
```

真实源码：

- [IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)
- [PlannerNode.handlePlanGenerate(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

工具类位置：
[StateUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/StateUtil.java)

### 3.3 这个仓库里 state 怎么写

这里有个容易误会的点：
节点通常不是直接改 `state`，而是返回一个 `Map<String, Object>`，由框架负责把这个 Map 合并回全局状态。

比如：

```java
return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, generator);
```

或者：

```java
return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
```

对应源码：

- [IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)
- [PlanExecutorNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)

所以你要建立一个新习惯：

- 这个 node 的“输出”不是只看返回类型
- 更要看它往 state 里写了哪个 key

### 3.4 读这个仓库，state key 应该怎么分类看

我建议你把 state key 按职责分成几类，不然很容易被一堆常量劝退。

第一类，入口输入：

- `INPUT_KEY`
- `AGENT_ID`
- `IS_ONLY_NL2SQL`

第二类，多轮上下文和控制信息：

- `MULTI_TURN_CONTEXT`
- `HUMAN_REVIEW_ENABLED`
- `HUMAN_FEEDBACK_DATA`
- `TRACE_THREAD_ID`

第三类，中间结构化结果：

- `INTENT_RECOGNITION_NODE_OUTPUT`
- `QUERY_ENHANCE_NODE_OUTPUT`
- `TABLE_RELATION_OUTPUT`
- `PLANNER_NODE_OUTPUT`

第四类，执行控制：

- `PLAN_CURRENT_STEP`
- `PLAN_NEXT_NODE`
- `PLAN_VALIDATION_STATUS`
- `PLAN_VALIDATION_ERROR`
- `PLAN_REPAIR_COUNT`

第五类，执行产物：

- `SQL_GENERATE_OUTPUT`
- `SQL_EXECUTE_NODE_OUTPUT`
- `PYTHON_GENERATE_NODE_OUTPUT`
- `PYTHON_EXECUTE_NODE_OUTPUT`
- `RESULT`

你后面无论读哪个 node，都先问自己三个问题：

1. 它从 state 里读了什么
2. 它往 state 里写了什么
3. 下游哪个节点或 dispatcher 会消费这些 key

## 4. `KeyStrategyFactory` 决定了这条图的状态风格

图编排里一个很容易被忽略的点是：
同一个 key 被多次写入时，到底该替换、追加，还是做别的合并。

这就是 `KeyStrategyFactory` 管的事。

这个仓库里，定义就在这里：
[DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

你往下看会发现，绝大多数 key 都是：

```java
keyStrategyHashMap.put(SOME_KEY, KeyStrategy.REPLACE);
```

这代表什么？

代表这个仓库的状态管理偏“当前态”，不是偏“历史态”。

换句话说，它更关心：

- 当前这次意图识别结果是什么
- 当前这条 SQL 是什么
- 当前下一跳该去哪
- 当前这轮计划校验是否通过

而不是把每一版中间结果都堆在同一个 state key 里。

### 为什么这里大量用 `REPLACE`

因为这个项目本质上是在跑一条执行链。

执行链里的很多变量天然就应该只有“最新值”：

- 当前计划
- 当前错误原因
- 当前 SQL
- 当前 Python 输出
- 当前下一步节点名

如果这些字段都做累积，后面的 node 反而要先做一轮“到底取最新还是取历史”的二次判断，复杂度会更高。

### 那历史怎么办

这就是很多人第一次读会混的地方。

这个仓库不是完全不要历史，而是把历史放在更适合它的地方。

最典型的例子就是多轮上下文：

[MultiTurnContextManager](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java)

它不是把历史堆在 state 某个 key 里，而是：

- 当前轮开始时 `beginTurn(...)`
- Planner 流式输出时 `appendPlannerChunk(...)`
- 本轮完成时 `finishTurn(...)`
- 人工拒绝计划时 `restartLastTurn(...)`

也就是说：

- state 负责当前执行态
- 上下文管理器负责跨轮历史

这就是一个很像真实工程的拆法。

## 5. 从 `StateGraph` 到 `CompiledGraph`，中间到底发生了什么

到这里，图纸已经有了，但图纸还不能跑。
真正能执行的是 `CompiledGraph`。

它的创建位置在：
[GraphServiceImpl 构造器](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

核心代码：

```java
this.compiledGraph = stateGraph.compile(
    CompileConfig.builder()
        .interruptBefore(HUMAN_FEEDBACK_NODE)
        .build()
);
```

这行代码你一定要多看两眼，因为它不只是“compile 一下”，它还带了一个非常关键的运行时语义：

- 跑到 `HUMAN_FEEDBACK_NODE` 之前先暂停

这个配置一旦没看懂，后面 `handleHumanFeedback(...)` 为什么能恢复执行，你就会觉得像魔法。

其实一点也不魔法，它只是明确告诉图运行器：

1. 这张图可以暂停
2. 暂停点在人工反馈节点之前
3. 外部有机会在这个时间点修改 state
4. 然后再继续跑

## 6. `CompiledGraph` 在这个仓库里主要用哪三个能力

### 6.1 `invoke(...)`

适合一次跑完，只关心最终结果。

这个仓库里典型用法在：
[GraphServiceImpl.nl2sql(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

核心代码：

```java
OverAllState state = compiledGraph
    .invoke(
        Map.of(IS_ONLY_NL2SQL, true, INPUT_KEY, naturalQuery, AGENT_ID, agentId),
        RunnableConfig.builder().build()
    )
    .orElseThrow();

return state.value(SQL_GENERATE_OUTPUT, "");
```

这个场景为什么适合 `invoke(...)`？

因为 MCP tool 调用 `nl2sql` 时，调用方最关心的是“最后给我 SQL”，而不是“中间节点怎么流式输出”。

### 6.2 `stream(...)`

适合边执行边产出中间结果。

这个仓库里的主前端分析入口，就是这么跑的。位置在：
[GraphServiceImpl.handleNewProcess(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

核心代码：

```java
Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(
    Map.of(
        IS_ONLY_NL2SQL, nl2sqlOnly,
        INPUT_KEY, query,
        AGENT_ID, agentId,
        HUMAN_REVIEW_ENABLED, humanReviewEnabled,
        MULTI_TURN_CONTEXT, multiTurnContext,
        TRACE_THREAD_ID, threadId
    ),
    RunnableConfig.builder().threadId(threadId).build()
);
```

这里你要看懂两个点：

第一，`Map.of(...)` 这部分就是本次图执行的初始 state。

第二，`threadId` 不是随便带的。
它不是只是给日志看的，而是后面恢复执行时找回同一条图上下文的关键。

### 6.3 `updateState(...)`

这是很多人第一次读 Spring AI Graph 会忽略的能力，但在这个仓库里它恰好是核心卖点之一。

位置在：
[GraphServiceImpl.handleHumanFeedback(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

核心代码：

```java
RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();
RunnableConfig updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);
Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
```

这段代码背后的真实语义是：

1. 这不是新开一条图
2. 这是找到之前那条已经暂停的图
3. 把人工反馈结果打进原有 state
4. 然后从暂停点继续跑

这就是为什么我前面说，这个仓库的人工反馈恢复是“真恢复”，不是“把前端参数重新带一遍再整链重跑”。

## 7. `invoke(...)`、`stream(...)`、`updateState(...)` 放在一起看，就清楚了

我建议你把 `CompiledGraph` 的三个常用动作这样记：

- `invoke(...)`
  适合把图当函数。给输入，拿最终状态。
- `stream(...)`
  适合把图当事件流。给输入，持续拿节点输出。
- `updateState(...)`
  适合把图当会话。找到某个 thread 对应的执行上下文，修改状态，再继续跑。

这个区分一旦清楚，你再看这个项目的两个入口就很自然了：

- 前端流式分析链：`stream(...)`
- MCP 的 `nl2sql` 工具：`invoke(...)`

## 8. 这个仓库的 Graph 真正难点，不在 node 多，而在“执行权是怎么流动的”

我建议你把主链看成三层，不要混。

### 第一层：计划生成

节点：
[PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

它负责的是：
把问题、Schema、证据、多轮上下文这些东西，整理成一个结构化的执行计划。

它不负责真的执行 SQL，也不负责决定图怎么跳。

### 第二层：计划推进

节点：
[PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)

它负责的是：

- 读计划
- 校验计划
- 看当前步
- 算出下一步应该执行什么

它本身依然不是跳转器。

### 第三层：节点跳转

典型 dispatcher：

- [PlanExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/PlanExecutorDispatcher.java)
- [SQLExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SQLExecutorDispatcher.java)
- [HumanFeedbackDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/HumanFeedbackDispatcher.java)

它们负责的是：

- 读 state 里的执行结果
- 把图带到下一跳

这三层如果混在一起，图会非常难维护。
这个仓库把它们拆开，是架构上很成熟的一步。

## 9. 给你一个“读 Graph 代码不迷路”的方法

以后你再看这个仓库里任何一个 Graph 相关类，按这个顺序读。

第一步，看它属于哪一层：

- 图定义层
- 节点执行层
- 跳转层
- 图运行层

第二步，看它读哪些 state key。

第三步，看它写哪些 state key。

第四步，看这些 key 下游被谁消费。

第五步，再看它和 `invoke` / `stream` / `updateState` 的关系。

你只要按这个顺序读，哪怕代码量大，也不会乱。

## 10. 这一层最容易混的几个点

### `StateGraph` 和 `CompiledGraph` 的区别

前者是定义，后者是执行。
不要把 `compile(...)` 当成一个可有可无的步骤，它是把“图纸”变成“可运行图实例”的关键。

### `OverAllState` 和节点返回值的区别

`OverAllState` 是当前共享状态。
节点返回的 `Map` 是“我要把哪些结果写回共享状态”。

### node 和 dispatcher 的区别

node 负责业务判断。
dispatcher 负责下一跳。

### 人工反馈恢复和整链重跑的区别

这个仓库走的是“原 thread 恢复”。
不是“前端再发一次完整请求，从头来过”。

## 11. 建议下一步怎么读

如果你读到这里，Graph 这一层已经有骨架了。接下来建议这样连读：

- 想把入口和恢复链读透，看 [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- 想把计划推进和节点跳转读透，看 [PlannerNode、PlanExecutorNode 与执行控制](../05-method-walkthrough/planner-plan-executor-methods.md)
- 想把主链串成一步一步的执行过程，看 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)

## 12. Graph 框架一次调度内部发生什么

从概念上可以理解为：

1. `CompiledGraph` 根据当前位置找到当前节点。
2. 调用当前 `NodeAction.apply(state)`。
3. 节点同步返回 Map，或返回包含流式 generator 的 Map。
4. 框架消费节点流，并把最终状态更新合并进 `OverAllState`。
5. `KeyStrategyFactory` 决定同名 key 是替换还是追加。
6. 如果是固定边，直接得到下一节点。
7. 如果是条件边，调用 `EdgeAction.apply(state)`。
8. dispatcher 返回字符串形式的节点名。
9. Graph 在条件映射表中解析为真实节点。
10. 到达 `END`、异常或中断点时停止。

### `stream(...)` 返回的不是模型 token

`CompiledGraph.stream(...)` 返回 `Flux<NodeOutput>`。其中可能包含 `StreamingOutput`，也可能未来出现其他节点输出类型。模型 token 已经先由节点和 `FluxUtil` 包装过一次，所以 GraphService 面对的是“带节点身份的图输出”。

### 人工中断的两阶段行为

编译时设置 `interruptBefore(HUMAN_FEEDBACK_NODE)` 后，Graph 在准备进入该节点前保存执行现场并暂停。收到反馈后：

```text
RunnableConfig(threadId)
 -> updateState(feedbackData)
 -> 生成 updatedConfig
 -> metadata 附加反馈
 -> stream(null, resumeConfig)
 -> 从保存位置继续
```

`null` 表示没有新的初始 state；恢复依赖的是 thread 对应的已保存状态。

### state 更新示例

假设 SQL 执行节点返回：

```java
Map.of(
    SQL_EXECUTE_NODE_OUTPUT, updatedResults,
    PLAN_CURRENT_STEP, currentStep + 1,
    SQL_GENERATE_COUNT, 0
)
```

框架不会丢掉 `AGENT_ID`、`EVIDENCE` 等其他 key，只会按策略更新这几个 key。随后 dispatcher 读取新的失败原因或步号，决定回生成节点还是计划执行器。
