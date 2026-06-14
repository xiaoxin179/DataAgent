# MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖

这几个主题表面上看很散：

- MCP / Tool
- 多轮上下文
- 人工反馈
- Graph 恢复执行

但它们在这个仓库里其实是一条线上的东西，核心都在回答一个问题：

- 当系统不是“一问一答就结束”，而是一个可持续执行、可插人工、可被外部工具复用的 Agent 时，后端该怎么组织？

这篇就围着这个问题展开。

## 1. 先把四块能力放到同一张图里

你可以把这几块能力理解成四层：

1. `@Tool` / MCP
   负责把仓库能力暴露给外部
2. 多轮上下文
   负责让同一 thread 的历史可延续
3. 人工反馈
   负责让用户在计划阶段插手
4. Graph 恢复执行
   负责让系统不是重跑，而是原地继续

这四层如果拆开看，都能讲一点。
但把它们串起来看，你才会明白这个仓库为什么适合做企业 Agent 讲解。

## 2. `@Tool` 在这个仓库里不是“顺手加个注解”

先看：
[McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)

里面两个典型工具方法：

```java
@Tool(...)
public List<Agent> listAgentsToolCallback(...) { ... }

@Tool(...)
public String nl2SqlToolCallback(...) throws GraphRunnerException { ... }
```

### 这两个方法分别代表了两种工具化思路

#### 第一种：简单能力直接工具化

`listAgentsToolCallback(...)` 这种就很直接：

- 一个查询
- 一次返回
- 不走 Graph 主链

这说明这个仓库没有为了“统一”而强行把所有 Tool 都塞进 Graph。

#### 第二种：核心能力通过 Tool 暴露

`nl2SqlToolCallback(...)` 则是把 Graph 的能力往外暴露：

```java
return graphService.nl2sql(nl2SqlRequest.naturalQuery(), nl2SqlRequest.agentId());
```

也就是说：

- Tool 调的是 GraphService
- GraphService 再调 `CompiledGraph.invoke(...)`

这让仓库核心能力既能服务前端页面，也能服务外部工具调用。

## 3. 为什么 MCP Tool 不直接走 `GraphController`

这个问题很值得单独讲，因为它能帮你区分两种完全不同的调用形态。

### 前端页面场景

入口在：
[GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

特点是：

- 需要过程
- 需要 SSE
- 需要 threadId
- 需要支持人工反馈

### Tool 场景

入口在：
[McpServerService.nl2SqlToolCallback(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)

特点是：

- 更偏同步
- 更关心最终结果
- 不需要前端过程展示

所以这两个入口本来就不该混在一起。

这也是为什么：

- 页面入口走 `stream(...)`
- Tool 入口走 `invoke(...)`

它们共享的是底层能力，不共享的是交互协议。

## 4. `@Tool` 只是开始，真正的工具注册链还要往下看

很多人第一次看 Spring AI Tool 时，会以为标个 `@Tool` 就结束了。
在这个仓库里，还远远没结束。

### 第一步：方法上标注 `@Tool`

位置还是在：
[McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)

这一步只是声明：

- 这个方法是可被工具化的方法

### 第二步：把方法打包成 `ToolCallbackProvider`

位置在：
[McpServerConfig.mcpServerTools(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java)

核心代码：

```java
@Bean
@McpServerTool
public ToolCallbackProvider mcpServerTools(McpServerService mcpServerService) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(mcpServerService)
        .build();
}
```

### 第三步：接进整个工具解析链

位置在：
[DataAgentConfiguration.toolCallbackResolver(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

这里又会借助：
[McpServerToolUtil.excludeMcpServerTool(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/McpServerToolUtil.java)

这个设计的关键点不是“代码多”，而是它说明作者知道：

- MCP Server 专用工具
- 普通 Spring Bean 工具

这两类工具不一定适合在同一个扫描时机里处理。

这是一种典型的“为了避免初始化顺序问题而主动分层”的工程设计。

## 5. 多轮上下文为什么不直接堆进 state

先看：
[MultiTurnContextManager](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java)

它自己维护了两份数据：

- `history`
- `pendingTurns`

### 先理解这两个概念

#### `history`

代表已经完成的轮次历史。

每个历史项里会记录：

- 用户问题
- 对应 Planner 计划

#### `pendingTurns`

代表正在进行但还没正式落历史的一轮。

比如：

- 用户刚发起本轮问题
- Planner 正在流式输出
- 本轮还没正式结束

### 为什么不把所有历史都堆进 `OverAllState`

因为多轮上下文和单次图执行结果不是一个维度的东西。

`OverAllState` 更适合放：

- 本轮执行时当前需要消费的状态

而多轮上下文更像：

- 会话级记忆

如果把这两类东西硬塞在一起，状态会越来越杂。

所以作者选择了：

- state 管当前执行态
- `MultiTurnContextManager` 管跨轮历史

这其实很合理。

## 6. 这套多轮上下文是怎么工作的

还是看 [MultiTurnContextManager](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java)。

### 新一轮开始时

```java
beginTurn(threadId, userQuestion)
```

这表示：

- 这一轮开始了
- 当前用户问题先记进 pending

### Planner 流式输出过程中

```java
appendPlannerChunk(threadId, chunk)
```

这表示：

- 把 Planner 当前吐出来的片段不断拼进本轮 planBuilder

### 正常完成时

```java
finishTurn(threadId)
```

这时会把本轮问题和最终 Planner 输出正式写进 `history`。

### 中断或取消时

```java
discardPending(threadId)
```

这时只会丢掉当前 pending，不碰已完成历史。

### 用户拒绝当前计划时

```java
restartLastTurn(threadId)
```

这一步非常关键，它会：

- 把上一轮最后入历史的计划拿掉
- 把对应问题重新放回 pending

这正是为了后面重生新计划时，不被旧计划污染上下文。

## 7. 人工反馈为什么不是“图跑完以后再修改”

关键在 [GraphServiceImpl 构造器](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)：

```java
this.compiledGraph = stateGraph.compile(
    CompileConfig.builder().interruptBefore(HUMAN_FEEDBACK_NODE).build()
);
```

这里最关键的不是 `compile(...)`，而是：

- `interruptBefore(HUMAN_FEEDBACK_NODE)`

### 这意味着什么

意味着图不会等 `HumanFeedbackNode` 跑完再停，而是：

- 在到这个节点之前先暂停

这样带来的效果是：

1. 计划已经生成并流给前端
2. 后续执行还没继续
3. 用户有机会批准、拒绝或补充反馈

这才是真正的 plan review。

如果你等图跑过去了再收反馈，那就不是“审核”，而是“事后纠偏”了。

## 8. `handleHumanFeedback(...)` 为什么是整条恢复链的核心

位置在：
[GraphServiceImpl.handleHumanFeedback(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

这段逻辑我建议你重点记 5 步：

1. 拿到 `threadId`
2. 组装 `feedbackData`
3. 如果用户拒绝计划，回滚多轮上下文
4. 用 `compiledGraph.updateState(...)` 更新原状态
5. 再用 `compiledGraph.stream(null, resumeConfig)` 从原位置继续

### 这里真正厉害的点不在“能恢复”

而在于它恢复的是：

- 原 thread 上的那条执行链

不是：

- 再 new 一次请求，从头重跑整图

这两者在企业场景里差别很大。

### 为什么这点这么重要

因为整链重跑会带来几个现实问题：

- 成本更高
- 中间上下文可能漂
- 已经看过的计划又得重新走一遍
- 人工审核点不稳定

而这个仓库的做法更像：

- 在暂停现场里打补丁
- 然后继续往下走

## 9. `HumanFeedbackNode` 和 `HumanFeedbackDispatcher` 是怎么配合的

看这两个类：

- [HumanFeedbackNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/HumanFeedbackNode.java)
- [HumanFeedbackDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/HumanFeedbackDispatcher.java)

### `HumanFeedbackNode` 干什么

它负责把当前反馈状态写回 state。

比如：

- 现在是不是还在等反馈
- 批准了还是拒绝了
- 下一步应不应该回到 Planner

### `HumanFeedbackDispatcher` 干什么

它负责根据这些状态决定下一跳：

- 继续等待
- 回 `PLAN_EXECUTOR_NODE`
- 回 `PLANNER_NODE`
- 或结束

这再次体现了这个仓库一贯的分层风格：

- node 写状态
- dispatcher 解释状态

## 10. 这套设计为什么很适合企业 Agent

因为企业场景真正经常需要的就是这几件事：

### 第一，计划先给人看

不是所有分析任务都适合让模型一口气跑到底。

### 第二，错了能局部修

用户如果不认可计划，希望回去改计划，而不是整链清空重来。

### 第三，上下文要能延续

同一个 thread 里的历史问题和历史计划要能被继续利用。

### 第四，同一套能力既能给页面用，也能给 Tool 用

页面要过程，Tool 要结果。
这个仓库把这两条路拆得很清楚。

所以这几个能力组合起来，不只是“功能多”，而是具备了一种比较成熟的 Agent 后端味道。

## 11. 建议接着读什么

- 想继续把恢复执行这条链按方法顺下来，看 [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- 想继续把 Graph 运行机制补齐，看 [StateGraph / CompiledGraph / OverAllState 深挖](./stategraph-compiledgraph-overallstate-deep-dive.md)
- 想继续把 Tool、模型注册表、基础设施串起来看，看 [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)

## 12. Tool 注册与调用的完整顺序

```text
McpServerService 方法上的 @Tool
 -> McpServerConfig.mcpServerTools
 -> MethodToolCallbackProvider 扫描方法
 -> 生成工具名、描述和参数 Schema
 -> ToolCallbackResolver 纳入可用工具
 -> MCP 客户端/模型选择工具
 -> 参数反序列化为 record
 -> 调用 listAgentsToolCallback / nl2SqlToolCallback
```

`nl2SqlToolCallback` 走同步 `GraphService.nl2sql -> CompiledGraph.invoke`，因为 Tool 调用需要一个最终字符串，不需要页面 SSE 的中间展示和连接生命周期。

## 13. 多轮状态的逐步变化

新问题开始：

```text
buildContext(threadId) 读取已完成历史
 -> beginTurn(threadId, query) 创建 PendingTurn
 -> Planner chunk 到达
 -> appendPlannerChunk 追加 StringBuilder
```

正常结束：

```text
finishTurn
 -> pendingTurns.remove
 -> 截断计划长度
 -> history.computeIfAbsent
 -> synchronized(deque)
 -> 超限时删最旧轮次
 -> 追加 ConversationTurn
```

取消时只 `discardPending`，不会删除此前已经完成的历史。拒绝计划时 `restartLastTurn` 会移除最新历史计划，并用原问题重新建立 pending turn。

### 线程安全边界

`ConcurrentHashMap` 只保证 Map 操作安全，不保证 value 中的 `ArrayDeque` 安全，所以修改同一个 deque 时还要 `synchronized (deque)`。这是“容器线程安全不等于容器内部对象线程安全”的典型例子。

## 14. 人工反馈恢复的准确顺序

1. 首次 Graph 执行在人工节点前暂停。
2. 前端保留相同 `threadId` 再次请求。
3. `graphStreamProcess` 因反馈文本非空进入 `handleHumanFeedback`。
4. 根据 `rejectedPlan` 生成 feedback 布尔值。
5. 拒绝时回滚上一版计划历史。
6. `updateState` 把反馈和多轮上下文写回旧 state。
7. feedback 同时放入 `RunnableConfig` metadata。
8. `stream(null, resumeConfig)` 恢复。
9. `HumanFeedbackNode` 读取反馈，写 `human_next_node`。
10. dispatcher 决定回 Planner、继续 PlanExecutor 或结束。
