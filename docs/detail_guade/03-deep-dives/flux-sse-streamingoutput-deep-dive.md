# Flux / SSE / StreamingOutput 深挖

这个仓库的“流”，如果你只看成一个 `Flux`，后面一定会越看越乱。

因为这里至少有三层流同时存在：

1. 模型流
2. Graph 节点流
3. SSE 网络流

这三层都是 `Flux`，但它们根本不是一回事。
你一旦把它们混在一起，读 `FluxUtil`、`GraphServiceImpl`、`GraphController` 时就会一直有一种“明明都叫流，怎么职责完全不同”的错位感。

这篇就专门把这三层拆开讲。

## 1. 第一层：模型流，代表模型正在吐 token

这一层最典型的类型是：

- `Flux<ChatResponse>`

真实入口在：
[StreamLlmService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)

比如：

```java
return registry.getChatClient()
    .prompt()
    .user(user)
    .stream()
    .chatResponse();
```

这段代码说明了一件很重要的事：

- 模型流是从 `ChatClient.stream().chatResponse()` 开始的

也就是说，这一层的语义是：

- 大模型此刻正在一段一段吐内容

### 这一层解决的是什么问题

它解决的是：

- 不用等模型整段说完
- 先拿到增量输出

对这个仓库来说，这非常重要，因为后面无论是 Planner、SQL 生成还是 Python 分析，用户都不想长时间盯着空白页面。

### 这一层还没解决什么

模型流还没解决：

- 这是哪个 Graph 节点产出的
- 最终结果要写回哪个 state key
- 前端要怎么识别这是 JSON、SQL 还是普通文本
- 这段流要怎么变成 SSE

这些都不是模型流该管的。

## 2. 第二层：节点流，代表 Graph 节点的输出

这一层最典型的类型有两个：

- `Flux<GraphResponse<StreamingOutput>>`
- `Flux<NodeOutput>`

先看第一种，它通常出现在 node 内部。
最关键的工具类是：
[FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)

再看第二种，它通常出现在 Graph 运行层。
典型位置是：
[GraphServiceImpl.handleNewProcess(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

```java
Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(
    Map.of(...),
    RunnableConfig.builder().threadId(threadId).build()
);
```

### 节点流和模型流最大的区别是什么

模型流只知道：

- 模型正在吐 token

节点流还额外知道：

- 这是谁吐的
- 这段输出属于哪个节点
- 流结束以后该把什么结果写回 Graph state

换句话说，节点流已经进入“工作流语义”了，不再只是“模型语义”。

### `StreamingOutput` 在这里扮演什么角色

`StreamingOutput` 你可以理解成：

- Graph 节点对外发流时的统一包装壳

它里面会带上：

- chunk 文本
- 节点名
- 当前 state
- 输出类型

这也是为什么 `GraphServiceImpl.handleNodeOutput(...)` 能知道“这段输出属于哪个节点”，而不是只看到一串裸文本。

## 3. 第三层：SSE 流，代表发给浏览器的网络流

这一层最典型的类型是：

- `Flux<ServerSentEvent<GraphNodeResponse>>`

入口在：
[GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

核心桥接对象是：

- `Sinks.Many<ServerSentEvent<GraphNodeResponse>>`

### 这一层和前两层最大的区别

这一层不再关心模型或节点内部怎么跑，它关心的是：

- 怎么把消息持续推给前端
- 浏览器断开时怎么停后台任务
- complete / error 事件怎么发

也就是说：

- 模型流解决“怎么吐”
- 节点流解决“谁在吐、吐完写哪”
- SSE 流解决“怎么发给前端”

## 4. `FluxUtil` 为什么是这三层之间最关键的桥

如果说 `GraphServiceImpl` 负责“节点流 -> SSE 流”，那 `FluxUtil` 负责的就是：

- 模型流 -> 节点流

看 [FluxUtil.createStreamingGenerator(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)：

```java
final StringBuilder collectedResult = new StringBuilder();
sourceFlux = sourceFlux.doOnNext(r -> collectedResult.append(ChatResponseUtil.getText(r)));

return toStreamingResponseFlux(
    nodeName,
    state,
    Flux.concat(preFlux, sourceFlux, sufFlux),
    () -> sourceMapper.apply(collectedResult.toString())
);
```

### 这段代码很像真实工程里的“统一流模板”

它同时做了三件事：

1. 让节点能在正式内容前后插提示信息
2. 让前端能边收到 chunk 边展示
3. 让节点在流结束后把完整结果写回 state

这个设计非常关键，因为它避免了一个很常见的问题：

- 想流式展示，就很难收完整结果
- 想收完整结果，就容易丢掉实时性

`FluxUtil` 就是把这两个需求绑在一起了。

## 5. 一个节点是怎么从模型流变成 Graph 节点流的

最典型的例子是 [IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)。

它的流程是：

1. `llmService.callUser(prompt)` 拿到 `Flux<ChatResponse>`
2. 用 `FluxUtil.createStreamingGenerator(...)` 包一层
3. 在 `sourceMapper` 里把最终结果解析成 DTO
4. 返回 `Map.of(INTENT_RECOGNITION_NODE_OUTPUT, generator)`

也就是说，节点真正返回给 Graph 的不是 DTO 本身，而是：

- 一个带过程流
- 且结束时能把 DTO 写进 state 的 generator

这个设计你一旦看懂，后面 Planner、SQL、Python 那些流式节点的写法就会很自然。

## 6. `GraphServiceImpl` 又是怎么把节点流变成 SSE 的

图真正启动之后，拿到的是：

- `Flux<NodeOutput>`

位置在：
[GraphServiceImpl.handleNewProcess(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

然后在 `subscribeToFlux(...)` 里订阅：

```java
Disposable disposable = nodeOutputFlux.subscribe(
    output -> handleNodeOutput(graphRequest, output),
    error -> handleStreamError(agentId, threadId, error),
    () -> handleStreamComplete(agentId, threadId)
);
```

真正把内容发出去的地方在：
[GraphServiceImpl.handleStreamNodeOutput(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

核心代码：

```java
GraphNodeResponse response = GraphNodeResponse.builder()
    .agentId(request.getAgentId())
    .threadId(threadId)
    .nodeName(node)
    .text(chunk)
    .textType(textType)
    .build();

context.getSink().tryEmitNext(ServerSentEvent.builder(response).build());
```

这一步做的事情就很明确了：

- `StreamingOutput`
  还是 Graph 内部节点输出
- `GraphNodeResponse`
  变成前端可识别的业务消息
- `ServerSentEvent`
  变成真正的网络事件

## 7. `TextType` 为什么在流式链路里这么重要

这个仓库的流式内容不是只有普通文本。
它可能会流出：

- JSON
- SQL
- Markdown
- 结果集

如果不把这些类型区分开，前端很难知道该怎么渲染，后端也很难知道哪一段是结构化块边界。

这就是为什么你会看到像这样的写法：

```java
Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign()))
...
Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()))
```

或者：

```java
Flux.just(ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()))
...
Flux.just(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()))
```

对应位置：

- [PlannerNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
- [SqlGenerateNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)
- [PythonGenerateNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonGenerateNode.java)

而 `GraphServiceImpl.handleStreamNodeOutput(...)` 会根据这些标记持续维护当前文本类型。

### 这一步解决的不是“美观”，而是边界识别

有了这些边界：

- 前端知道当前是 SQL 还是 JSON
- 后端知道哪些标记本身不该展示给前端
- 后续结构化解析也更稳

## 8. 为什么 SSE 生命周期必须自己管

很多刚开始做流式接口的人，容易把重点全放在“怎么把数据发出去”，忽略“什么时候停”和“异常时怎么收”。

这个仓库在这方面处理得比较完整。

看 [GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)：

```java
.doOnCancel(() -> { ... })
.doOnError(e -> { ... })
.doOnComplete(() -> { ... })
```

再看 [GraphServiceImpl.stopStreamProcessing(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)。

### 为什么这些逻辑不能偷懒

因为浏览器断开，不代表后台 Graph 会自动停。
如果不做这些清理，后果很直接：

- 模型还在继续调
- 数据库还在继续查
- 线程还在继续占
- stream context 还在内存里挂着

对长链路系统来说，这就是典型资源泄漏。

## 9. `complete` 和 `error` 为什么要显式发事件

再看这两个方法：

- [GraphServiceImpl.handleStreamComplete(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [GraphServiceImpl.handleStreamError(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

它们会显式发：

- `STREAM_EVENT_COMPLETE`
- `STREAM_EVENT_ERROR`

这一步很重要，因为前端不只是要文本内容，还要知道：

- 这次任务是正常结束
- 还是中间失败

如果没有这个信号，前端就只能靠连接是否断开去猜，这在复杂场景下很不稳。

## 10. 这三层流最容易混的几个点

### 模型流不是 SSE 流

`Flux<ChatResponse>` 还没到 HTTP 层。
它只是模型内部输出。

### 节点流不是最终前端协议

`Flux<GraphResponse<StreamingOutput>>` 还是 Graph 内部语义。
它知道节点和 state，但前端不会直接消费这个类型。

### SSE 不负责业务决策

SSE 只负责发。
它不会决定：

- 下一个节点去哪
- 当前计划合不合法
- SQL 要不要重生成

这些事都在 Graph 和 node/dispatcher 层。

## 11. 这套流式设计为什么值得学

因为它没有把“流式输出”简单理解成：

- 直接把模型 token 往浏览器转发

它做得更完整：

1. 模型层先吐原始 token
2. 节点层把 token 包装成带业务语义的输出
3. Graph 层把节点输出组织进整条执行链
4. HTTP 层再把结果稳定推给前端

这四层拆开以后：

- 哪层出问题，好定位
- 哪层要改，不容易串味
- 想加新展示类型，也更容易扩展

## 12. 建议接着读什么

- 想继续把入口和恢复链路读透，看 [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- 想继续把流包装工具读透，看 [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)
- 想继续把主链顺着看下来，看 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)

## 13. 三层流的类型转换表

| 阶段 | Java 类型 | 谁生产 | 谁消费 |
|---|---|---|---|
| 模型流 | `Flux<ChatResponse>` | `LlmService` | `FluxUtil` |
| 节点流 | `Flux<GraphResponse<StreamingOutput>>` | `FluxUtil` | Graph 框架 |
| 图输出流 | `Flux<NodeOutput>` | `CompiledGraph` | `GraphServiceImpl` |
| SSE 事件 | `ServerSentEvent<GraphNodeResponse>` | `GraphServiceImpl` | sink |
| HTTP 流 | `Flux<ServerSentEvent<GraphNodeResponse>>` | `sink.asFlux()` | WebFlux/浏览器 |

### 一个 chunk 的旅程

1. 模型返回一个 `ChatResponse`。
2. `FluxUtil` 提取文本并创建 `StreamingOutput(nodeName, chunk)`。
3. Graph 把它作为 `NodeOutput` 发给订阅者。
4. `subscribe` 的 `onNext` 参数 `output` 接到该对象。
5. `handleNodeOutput` 判断它是 `StreamingOutput`。
6. `handleStreamNodeOutput` 读取节点名和 chunk。
7. `TextType` 判断该 chunk 是否只是类型边界标记。
8. 非标记内容写入 `outputCollector`；Planner 内容额外写入多轮 pending turn。
9. 构造 `GraphNodeResponse`。
10. `tryEmitNext` 把 SSE 事件送入 sink。

### `subscribe` 三个 Lambda 的准确含义

```java
nodeOutputFlux.subscribe(
    output -> handleNodeOutput(...),    // onNext，每个元素一次
    error -> handleStreamError(...),    // onError，终止信号
    () -> handleStreamComplete(...)     // onComplete，无参数终止信号
);
```

`output` 的实参由上游 `nodeOutputFlux` 每次发出的元素自动传入，不是业务代码手工调用 Lambda。
