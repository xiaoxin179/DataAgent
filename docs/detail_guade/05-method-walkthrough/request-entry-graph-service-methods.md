# 请求入口、Graph 启动、人工反馈恢复：关键方法逐段讲解

这篇我们不再只盯 4 个方法，而是把“前端点一下开始分析”之后，后端真正发生了什么，完整串起来。

建议你边看边打开这两个文件：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)
- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

这两个类是整条主链最靠近入口的一段。你如果这里没看顺，后面 node 再熟也会觉得断。

## 先抓主线，不要一上来钻细节

请求主线其实很简单：

1. `GraphController.streamSearch(...)` 接 HTTP 请求
2. 建一个 SSE sink
3. 把参数封成 `GraphRequest`
4. 交给 `GraphServiceImpl.graphStreamProcess(...)`
5. `GraphServiceImpl` 判断这是新执行还是恢复执行
6. 真正启动 `compiledGraph.stream(...)`
7. 把图输出转成 SSE 一路推回前端

你后面读代码时，脑子里只要一直带着这条线，就不容易丢。

## 1. `GraphController.streamSearch(...)`

源码位置：
[GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

### 它在整条链路里的定位

这是前端主分析入口，但它不是业务编排入口。
它的职责比较克制，主要就是做协议层适配。

说白了就是：

- 接参数
- 建 SSE 通道
- 把活转给 GraphService

### 方法核心片段

```java
Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink =
    Sinks.many().unicast().onBackpressureBuffer();

GraphRequest request = GraphRequest.builder()
    .agentId(agentId)
    .threadId(threadId)
    .query(query)
    .humanFeedback(humanFeedback)
    .humanFeedbackContent(humanFeedbackContent)
    .rejectedPlan(rejectedPlan)
    .nl2sqlOnly(nl2sqlOnly)
    .build();

graphService.graphStreamProcess(sink, request);

return sink.asFlux()...;
```

### 先看输入，别急着看返回值

它收的参数里，最重要的其实就 4 个：

- `agentId`
  这决定本次分析归哪个智能体，背后会影响模型、知识库、数据源。
- `threadId`
  这决定本次是不是一条已有执行链的续跑。
- `query`
  用户问题本体。
- `humanFeedbackContent`
  只要这里有值，后面大概率就不是新执行，而是恢复执行。

另外几个开关参数：

- `humanFeedback`
- `rejectedPlan`
- `nl2sqlOnly`

都是控制执行分支的。

### 这一段真正做了什么

#### 第一件事，设 SSE 响应头

```java
response.getHeaders().add("Cache-Control", "no-cache");
response.getHeaders().add("Connection", "keep-alive");
response.getHeaders().add("Access-Control-Allow-Origin", "*");
```

这一步不是可有可无。
SSE 本身是长连接，如果你这里不把连接和缓存语义处理好，前端体验会很差。

#### 第二件事，建 `Sinks.Many`

```java
Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink =
    Sinks.many().unicast().onBackpressureBuffer();
```

这行一定要看懂。

- `unicast`
  表示这条流只面向一个订阅者，这和“一个浏览器连接对应一次分析任务”很匹配。
- `onBackpressureBuffer`
  表示前端消费稍慢时，先缓冲，而不是直接把数据丢掉。

这里的 `sink`，你可以把它理解成：

- 后端主动往前端 SSE 连接里写事件的“手动推送口”

#### 第三件事，组装 `GraphRequest`

它把 HTTP 世界的零散参数，整理成 GraphService 看得懂的请求对象。

这一步的意义在于：

- Controller 不要把业务执行的细节分散在参数列表里
- 让后续逻辑只关心一个统一对象

#### 第四件事，把执行权交出去

```java
graphService.graphStreamProcess(sink, request);
```

从这里开始，Controller 基本就退出主舞台了。
后面真正的 Graph 执行、恢复、取消、异常处理，都在 `GraphServiceImpl` 里。

### 返回值为什么不是普通对象，而是 `Flux<ServerSentEvent<GraphNodeResponse>>`

因为这里的返回目标根本不是“最终结果对象”，而是“一条持续产出事件的流”。

这和传统 Spring MVC 接口的差别非常大：

- MVC 常见是：请求来，算完，返回 JSON
- 这里是：请求来，先建通道，再持续往里推内容

这个差别你一旦没立住，后面 `sink`、`Flux`、`NodeOutput` 看着都会别扭。

### `doOnCancel(...)` 为什么很重要

```java
.doOnCancel(() -> {
    if (request.getThreadId() != null) {
        graphService.stopStreamProcessing(request.getThreadId());
    }
})
```

很多人第一次写 SSE，容易只盯“怎么往前推数据”，忽略“前端断了以后后端怎么办”。

这里做得比较工程化：

- 浏览器断开
- 页面关闭
- 用户主动取消

这些都会触发 `doOnCancel(...)`，然后后端会去停掉同一个 `threadId` 对应的后台执行。

否则会怎样？

- 模型还在继续跑
- 数据库还在继续查
- 线程还在继续占着

前端都没了，后端还在烧资源。

### 一句话总结

`streamSearch(...)` 的本质是：
把一次 HTTP 请求，变成一条可持续推送 Graph 执行过程的 SSE 流。

## 2. `GraphServiceImpl.graphStreamProcess(...)`

源码位置：
[GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

### 它的定位

这是 Controller 和 Graph 之间的总调度入口。

Controller 到这里为止，还是“Web 请求语义”。
到了这里，才真正开始进入“图执行语义”。

### 方法核心片段

```java
if (!StringUtils.hasText(graphRequest.getThreadId())) {
    graphRequest.setThreadId(UUID.randomUUID().toString());
}
String threadId = graphRequest.getThreadId();

StreamContext context = streamContextMap.computeIfAbsent(threadId, k -> new StreamContext());
context.setSink(sink);

if (StringUtils.hasText(graphRequest.getHumanFeedbackContent())) {
    handleHumanFeedback(graphRequest);
}
else {
    handleNewProcess(graphRequest);
}
```

### 这段代码真正重要的不是分支，而是 `threadId`

这个方法最核心的事情，不是 `if else`，而是先把这次请求挂到一个明确的 thread 上。

#### 为什么必须先确定 `threadId`

因为后面几乎所有关键动作都依赖它：

- 多轮上下文按 `threadId` 组织
- 流式上下文按 `threadId` 缓存
- 人工反馈恢复按 `threadId` 找回执行链
- tracing 也会跟 `threadId` 走

所以这里如果前端没传 `threadId`，后端会先补一个：

```java
graphRequest.setThreadId(UUID.randomUUID().toString());
```

#### `StreamContext` 是干什么的

```java
StreamContext context = streamContextMap.computeIfAbsent(threadId, k -> new StreamContext());
context.setSink(sink);
```

`StreamContext` 可以理解成：

- 这条 thread 当前这次流式执行的现场

里面会保存：

- SSE sink
- Reactor 订阅句柄
- 当前文本类型
- 已累计输出
- tracing span

所以 `graphStreamProcess(...)` 做的不是“直接跑图”，而是先把这次请求绑定到一个可管理的执行现场上。

#### 它怎么判断“新请求”还是“恢复请求”

判断条件非常直接：

```java
if (StringUtils.hasText(graphRequest.getHumanFeedbackContent())) {
    handleHumanFeedback(graphRequest);
}
else {
    handleNewProcess(graphRequest);
}
```

这里的思路挺实用：

- 有反馈内容，说明前面已经执行过一轮，并且执行链在某个地方停住了
- 没反馈内容，那就是一次全新的执行

### 一句话总结

`graphStreamProcess(...)` 的本质是：
先把请求挂到 thread 上，再决定这次是“首次开跑”还是“从暂停点恢复”。

## 3. `GraphServiceImpl.handleNewProcess(...)`

### 它的定位

这是新请求真正开始跑图的地方。

如果说 `graphStreamProcess(...)` 负责“分诊”，那这里就是“正式接诊”。

### 方法核心片段

```java
String multiTurnContext = multiTurnContextManager.buildContext(threadId);
multiTurnContextManager.beginTurn(threadId, query);

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

subscribeToFlux(context, nodeOutputFlux, graphRequest, agentId, threadId);
```

### 先看它给图塞了哪些初始状态

这段 `Map.of(...)` 非常值得逐个看，因为这就是本次图执行的起始 state。

- `IS_ONLY_NL2SQL`
  控制是不是只跑 NL2SQL 精简链。
- `INPUT_KEY`
  用户问题。
- `AGENT_ID`
  当前智能体。
- `HUMAN_REVIEW_ENABLED`
  这次是否开启人工复核。
- `MULTI_TURN_CONTEXT`
  多轮上下文文本。
- `TRACE_THREAD_ID`
  tracing 和流式链路都要用。

也就是说，这里做的事不是“调用一个大方法跑图”，而是：

- 把本次运行所需的初始上下文喂给 Graph

### 为什么先 `buildContext(...)`，再 `beginTurn(...)`

看这两行：

```java
String multiTurnContext = multiTurnContextManager.buildContext(threadId);
multiTurnContextManager.beginTurn(threadId, query);
```

顺序不是随便写的。

先 `buildContext(...)` 的意思是：

- 先取“之前已完成轮次”的上下文，作为这次 prompt 的历史背景

再 `beginTurn(...)` 的意思是：

- 从现在开始，记录本轮用户问题和后续 Planner 输出

这保证了：

- 本轮开始时不会把“本轮还没生成的计划”错误地带进 prompt
- 本轮结束后，又能把新计划补进历史

多轮上下文管理类在这里：
[MultiTurnContextManager](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java)

### 为什么这里走 `compiledGraph.stream(...)`，不是 `invoke(...)`

因为前端主分析链需要的是过程，而不是只有结果。

这条链里会持续产出：

- 意图识别说明
- 证据召回结果
- 计划 JSON
- SQL 生成过程
- Python 分析过程
- 最终报告

这些都适合流式往前推。

### `RunnableConfig.builder().threadId(threadId).build()` 不是装饰品

很多人会把这行当成“顺手传个参数”。其实它非常关键。

这行的作用是：

- 明确告诉 Graph：这次执行属于哪个 thread

没有它，后面就很难做真正的恢复执行。

### `subscribeToFlux(...)` 为什么单独拆出来

因为启动图和订阅图，最好分开。

这里的设计思路是：

- 先把 SSE 连接立住
- 再在后台线程异步订阅图输出

否则如果你在请求线程里把后面的整个订阅链都做完，长链路下体验会更脆。

### 一句话总结

`handleNewProcess(...)` 是一次新分析任务真正开始执行 Graph 的地方，它负责把用户请求转成图的初始 state，然后启动流式执行。

## 4. `GraphServiceImpl.handleHumanFeedback(...)`

### 它的定位

这是这个仓库最值得学的一段之一。
因为它不是“收到反馈后整链重跑”，而是“在原执行现场上恢复继续跑”。

### 方法核心片段

```java
Map<String, Object> feedbackData = Map.of(
    "feedback", !graphRequest.isRejectedPlan(),
    "feedback_content", feedbackContent
);

Map<String, Object> stateUpdate = new HashMap<>();
stateUpdate.put(HUMAN_FEEDBACK_DATA, feedbackData);
stateUpdate.put(MULTI_TURN_CONTEXT, multiTurnContextManager.buildContext(threadId));

RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();
RunnableConfig updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);

RunnableConfig resumeConfig = RunnableConfig.builder(updatedConfig)
    .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackData)
    .build();

Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
```

### 先说这段代码最关键的判断

它不是从头构造一个新的初始 state，再 `stream(...)`。
它做的是：

1. 找到旧 thread
2. 改旧 state
3. 从旧暂停点继续跑

这个差别非常大。

### 为什么先构造 `feedbackData`

```java
Map<String, Object> feedbackData = Map.of(
    "feedback", !graphRequest.isRejectedPlan(),
    "feedback_content", feedbackContent
);
```

这里把前端的反馈信息转成图内部能理解的标准结构。

这个结构后面会进入：

- state
- runnable metadata

也就是说，后续无论是 node 还是 dispatcher，都可以基于同一份反馈数据继续判断。

### 用户拒绝计划时，为什么要 `restartLastTurn(...)`

```java
if (graphRequest.isRejectedPlan()) {
    multiTurnContextManager.restartLastTurn(threadId);
}
```

这个细节非常像真实工程里的处理方式。

为什么要回滚？

因为如果上一版 Planner 计划已经被记录到多轮上下文里，而用户又明确拒绝了它，那这版计划就不该继续污染后面的 prompt。

所以这里会：

- 把上一轮最后记录的计划拿掉
- 重新把这轮问题放回 pending 状态

这样下一版新计划生成时，历史上下文不会混入“已经被用户否掉的旧计划”。

### `updateState(...)` 是整段的灵魂

```java
RunnableConfig updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);
```

这行的含义是：

- 不重建执行链
- 直接修改同一个 `threadId` 对应的图状态

所以你可以把它理解成：

- 对暂停中的图做一次“补充输入”

### 为什么恢复执行时是 `stream(null, resumeConfig)`

```java
Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
```

这里第一个参数传 `null`，恰好很能说明问题。

因为这次不是“从头开跑”，不需要新的初始输入 Map。
这次的执行上下文已经在老 thread 里了，前面 `updateState(...)` 只是给那份老状态打补丁。

### 一句话总结

`handleHumanFeedback(...)` 的本质是：
在已经暂停的图执行上下文上补充人工反馈，然后从原位置继续跑。

## 5. `subscribeToFlux(...)`：别漏掉这个方法

很多人看入口链路时，只盯到 `compiledGraph.stream(...)` 就停了。
其实真正把图输出接到 SSE 上的关键桥接，还在 `subscribeToFlux(...)`。

### 它做了什么

核心代码：

```java
Disposable disposable = nodeOutputFlux.subscribe(
    output -> handleNodeOutput(graphRequest, output),
    error -> handleStreamError(agentId, threadId, error),
    () -> handleStreamComplete(agentId, threadId)
);
```

这三件事分别对应：

- 每来一个节点输出，怎么处理
- 整条流报错时，怎么收尾
- 整条流完成时，怎么收尾

### 为什么要保存 `Disposable`

因为浏览器可能随时断开。
一旦断开，后端需要有能力把正在跑的 Reactor 订阅停掉。

否则前端没了，后台还在跑。

## 6. `handleNodeOutput(...)` 和 `handleStreamNodeOutput(...)`：SSE 真正发数据的地方

你如果想知道“前端到底是从哪收到一段段文本的”，关键要看这里。

### 先看分发

```java
if (output instanceof StreamingOutput streamingOutput) {
    handleStreamNodeOutput(request, streamingOutput);
}
```

也就是说，这里真正重点处理的是 `StreamingOutput`。

### 再看发流

```java
GraphNodeResponse response = GraphNodeResponse.builder()
    .agentId(request.getAgentId())
    .threadId(threadId)
    .nodeName(node)
    .text(chunk)
    .textType(textType)
    .build();

Sinks.EmitResult result = context.getSink()
    .tryEmitNext(ServerSentEvent.builder(response).build());
```

这段代码做了两件事：

1. 把图层输出包装成前端可消费的 `GraphNodeResponse`
2. 真正往 SSE sink 推送事件

### 为什么这里还要处理 `TextType`

因为仓库里有一类输出不是普通文本，而是结构化 JSON 块。

比如 Planner 输出计划时，会手动拼：

- JSON 起始标记
- JSON 正文
- JSON 结束标记

然后这里会根据这些标记识别：

- 当前是普通文本
- 还是 JSON 文本

这样前端展示层就能按类型决定怎么渲染。

### 为什么 Planner 输出还要额外缓存

```java
if (PlannerNode.class.getSimpleName().equals(node)) {
    multiTurnContextManager.appendPlannerChunk(threadId, chunk);
}
```

这一行很关键。

它说明：

- Planner 的流式输出，不只是给前端看的
- 后端还会把它积累起来，作为本轮计划历史的一部分

后面如果本轮顺利结束，就会在 `finishTurn(...)` 里正式入历史；如果被拒绝，还能用 `restartLastTurn(...)` 回滚。

## 7. `handleStreamError(...)` 和 `handleStreamComplete(...)`：别把收尾逻辑看轻

这两个方法做的事看起来像“善后”，其实直接决定系统稳不稳。

### 正常完成时做了什么

```java
multiTurnContextManager.finishTurn(threadId);
...
context.getSink().tryEmitNext(ServerSentEvent.builder(
    GraphNodeResponse.complete(agentId, threadId)
).event(STREAM_EVENT_COMPLETE).build());
context.getSink().tryEmitComplete();
context.cleanup();
```

重点是三件事：

1. 把本轮计划正式记入多轮历史
2. 告诉前端这条链已经完成
3. 清理上下文

### 异常时做了什么

```java
context.getSink().tryEmitNext(ServerSentEvent.builder(
    GraphNodeResponse.error(agentId, threadId, ...)
).event(STREAM_EVENT_ERROR).build());
context.getSink().tryEmitComplete();
context.cleanup();
```

重点也是三件事：

1. 上报 tracing 错误
2. 给前端一个明确 error 事件
3. 释放资源

如果没有这层统一收尾，SSE 场景下最容易出现的就是：

- 前端一直等不到结束信号
- 后台上下文没清理干净
- thread 现场泄漏

## 8. 这一段代码最值得学的设计取舍

### Controller 很克制

Controller 不碰 Graph 细节，不碰 state，不碰 node。
只做协议层转换。

### GraphService 是真正的运行协调器

它不负责具体 AI 业务推理，但负责：

- thread 生命周期
- SSE 生命周期
- Graph 启停与恢复
- tracing 收尾

### 人工反馈恢复是“原地恢复”，不是“重跑”

这是这个仓库很像企业实战的一点。

### 多轮上下文没有硬塞进 state 历史堆里

而是通过 `MultiTurnContextManager` 单独管理。

## 9. 建议你下一步怎么连读

如果你这篇已经顺了，接下来建议这样读：

- 想把 Graph 本体机制补齐，看 [StateGraph / CompiledGraph / OverAllState 深挖](../03-deep-dives/stategraph-compiledgraph-overallstate-deep-dive.md)
- 想把 Flux 和 SSE 这层补齐，看 [Flux / SSE / StreamingOutput 深挖](../03-deep-dives/flux-sse-streamingoutput-deep-dive.md)
- 想把模型注册、流包装、MCP Tool 一起补齐，看 [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](./utility-registry-mcp-methods.md)

## 10. 方法级精确调用表

### `streamSearch(...)`

| 顺序 | 代码动作 | 结果 |
|---|---|---|
| 1 | 写 SSE/no-cache 响应头 | 浏览器和代理按长连接处理 |
| 2 | 创建 `unicast().onBackpressureBuffer()` | 一个请求对应一个消费者 |
| 3 | Builder 构造 `GraphRequest` | HTTP 参数进入领域请求对象 |
| 4 | 调 `graphStreamProcess(sink, request)` | 后台开始运行 |
| 5 | `sink.asFlux()` | 把手动生产端暴露为消费流 |
| 6 | `filter` | 丢空文本，保留 error/complete |
| 7 | 注册生命周期回调 | 取消、异常时停止后台任务 |

注意：`graphStreamProcess` 可能在内部给 request 补 `threadId`。Controller 后续 Lambda 捕获的是同一个 request 对象，所以能读到补写后的值。

### `graphStreamProcess(...)`

```text
检查 threadId
 -> 空则 UUID
 -> computeIfAbsent 创建 StreamContext
 -> context.setSink
 -> 检查 humanFeedbackContent
 -> handleHumanFeedback 或 handleNewProcess
```

`computeIfAbsent` 的 mapping function 只在 key 没有对应 value 时执行。这里实现“一条 thread 一个活动上下文”；并不代表 `StreamContext` 内部字段天然线程安全。

### `handleNewProcess(...)`

1. 读取 query、agentId、threadId 和控制开关。
2. 校验必填参数。
3. 从 Map 取回前面建立的 context。
4. 检查 context 是否已经 cleanup。
5. 创建 Langfuse/OpenTelemetry span 并放入 context。
6. `buildContext` 只读取过去已完成轮次。
7. `beginTurn` 登记当前问题。
8. 构造初始 state Map。
9. 构造携带 threadId 的 `RunnableConfig`。
10. 调 `compiledGraph.stream(...)` 获得图输出 Flux。
11. 调 `subscribeToFlux(...)` 建立消费。

### `subscribeToFlux(...)`

`CompletableFuture.runAsync(..., executor)` 把订阅建立动作交给专用线程池。`subscribe` 返回 `Disposable` 后进入 `synchronized(context)`，是为了处理“订阅刚建立，另一线程同时 cleanup”的竞态：

- context 已清理：立即 dispose 新订阅。
- context 未清理：把 disposable 保存进 context。

如果缺少这个二次检查，取消请求可能发生在 `subscribe()` 与 `setDisposable()` 之间，导致一个已经无人消费的后台流继续运行。

### `handleStreamComplete(...)`

正确顺序是：

```text
finishTurn
 -> streamContextMap.remove
 -> endSpanSuccess
 -> sink.tryEmitNext(complete event)
 -> sink.tryEmitComplete
 -> context.cleanup
```

先 `remove` 再清理能让并发的停止/错误路径只有一个调用者拿到 context；`cleanup` 内部还有 `AtomicBoolean.compareAndSet(false, true)` 作为第二道幂等保护。

### `handleStreamError(...)`

异常路径不会调用 `finishTurn`，因此未完成计划不会进入正式历史。它会移除 context、结束失败 span、发送 error 事件、完成 sink 并 cleanup。前端既收到业务级 error 事件，也会最终收到流结束信号。
