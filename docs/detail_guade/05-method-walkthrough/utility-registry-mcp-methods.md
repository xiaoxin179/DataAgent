# FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool：关键方法逐段讲解

这一篇讲的几类代码，看起来分散，实际上它们一起构成了这个仓库很关键的一圈基础设施：

- `FluxUtil`
  负责把模型流包装成 Graph 能消费、前端也能展示的节点流
- `JsonParseUtil`
  负责把不稳定的模型 JSON 输出尽量救回来
- `AiModelRegistry`
  负责把“当前生效模型”统一拿出来
- `DynamicModelFactory`
  负责把数据库里的模型配置变成真的 Spring AI 模型实例
- `McpServerService`
  负责把仓库能力暴露成 Tool

如果说 node 层决定“业务做什么”，那这几个类决定的是“系统怎么把这些能力稳定地组织起来”。

## 先给一个阅读建议

这篇不要把它看成 5 个互不相干的工具类。

你可以按这个顺序理解：

1. 模型到底从哪来
2. 模型流出来的内容怎么包装
3. 模型输出的 JSON 不稳时怎么办
4. 仓库能力怎么暴露给外部 Tool

这样读，就不会碎。

## 1. `AiModelRegistry.getChatClient()`

源码位置：
[AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)

### 先说它的定位

这个方法不是单纯“返回一个 Bean”。
它是整个聊天模型调用链的统一入口。

换句话说，业务层不应该关心：

- 当前到底用哪个厂商
- 当前模型配置存在数据库哪条记录
- 当前模型实例有没有缓存

这些都应该收在这里。

### 先看核心代码

```java
if (currentChatClient == null) {
    synchronized (this) {
        if (currentChatClient == null) {
            ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
            ChatModel chatModel = modelFactory.createChatModel(config);
            currentChatClient = ChatClient.builder(chatModel).build();
        }
    }
}
return currentChatClient;
```

### 这段代码其实做了 3 件事

#### 第一件事，懒加载

它不是项目启动时就强行把所有模型都初始化好，而是：

- 第一次真有人要用 ChatClient 时，才去建

这很合理，因为模型实例化和底层 HTTP 客户端装配都不算便宜。

#### 第二件事，缓存

模型实例建完以后，不会每次请求都重新 new 一遍。

否则会怎样？

- 每次调用都重新读配置
- 每次都重新建 HTTP 客户端
- 每次都重新建模型实例

这显然很亏。

#### 第三件事，热切换预留

注意它还有：

```java
public void refreshChat() {
    this.currentChatClient = null;
}
```

这意味着：

- 平时走缓存
- 需要切模型时，清空缓存
- 下次请求再按最新配置重新建

这就是这个仓库支持“动态模型切换”的关键入口之一。

### 为什么这里返回的是 `ChatClient`，不是 `ChatModel`

因为对大部分业务节点来说，它更关心“怎么组织一次 prompt 调用”，而不是底层模型协议细节。

所以这个仓库干脆把 `ChatModel` 再包装一层，统一对业务暴露 `ChatClient`。

这样业务层的调用就会很干净：

```java
return registry.getChatClient().prompt().user(user).stream().chatResponse();
```

对应源码：
[StreamLlmService.callUser(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)

### 你读这段代码时要特别注意什么

- 它解决的是“当前该用哪个模型”的问题
- 它不是模型配置存储层
- 它也不是 prompt 组织层

一句话说透：

`AiModelRegistry` 是“模型实例的运行时入口”，不是“模型配置管理后台”。

## 2. `DynamicModelFactory.createChatModel(...)`

源码位置：
[DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)

### 它的定位

如果说 `AiModelRegistry` 负责“现在用谁”，那 `DynamicModelFactory` 负责“把配置翻译成能跑的模型实例”。

### 方法核心片段

```java
checkBasic(config);

OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
    .apiKey(apiKey)
    .baseUrl(config.getBaseUrl())
    .restClientBuilder(getProxiedRestClientBuilder(config))
    .webClientBuilder(getProxiedWebClientBuilder(config));

if (StringUtils.hasText(config.getCompletionsPath())) {
    apiBuilder.completionsPath(config.getCompletionsPath());
}

OpenAiApi openAiApi = apiBuilder.build();

OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
    .model(config.getModelName())
    .temperature(config.getTemperature())
    .maxTokens(config.getMaxTokens())
    .streamUsage(true)
    .build();

return OpenAiChatModel.builder()
    .openAiApi(openAiApi)
    .defaultOptions(openAiChatOptions)
    .build();
```

### 这段代码别只看类名，要看它的设计取舍

#### 第一层，统一走 OpenAI 协议适配

这个仓库没有为不同厂商写很多套完全平行的模型接入代码，而是选择：

- 统一尽量走 OpenAI 兼容协议
- 通过 `baseUrl`、路径和鉴权差异做适配

这在工程上很务实。
因为很多模型服务现在都支持 OpenAI 风格接口。

#### 第二层，把同步和异步客户端都装进去

你会看到它同时设置了：

- `restClientBuilder(...)`
- `webClientBuilder(...)`

这是因为 Spring AI 内部既可能有同步调用，也可能有异步流式调用。
如果只配一套，很容易在某些场景下“看起来能用，实际一部分调用没走代理或没带超时”。

#### 第三层，把代理配置也收进来

这个工厂里不只是“建模型”，它还负责：

- 是否启用代理
- 代理 host / port
- 代理认证

这样上层业务就不需要再碰这些网络细节。

### 为什么这里值得认真看

因为它非常像真实企业里的模型接入层：

- 配置来自数据库
- 模型提供方可能经常换
- 有的走公网，有的要代理
- 有的路径不标准

这些脏活，最适合关在工厂里。

### 一个容易忽略但很有用的点

```java
checkBasic(config);
```

这里做了最基本的防御：

- `baseUrl` 不能为空
- 非 custom provider 的 `apiKey` 不能为空
- `modelName` 不能为空

这类基础校验看起来普通，但如果没有，错误会拖到真正发请求的时候才炸，定位成本会高很多。

## 3. `FluxUtil.createStreamingGenerator(...)`

源码位置：
[FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)

### 它的定位

这是整个仓库里最实用的一个流包装工具之一。

一句话概括它干的事：

- 上游是模型流
- 下游既要前端看过程，又要 Graph state 留最终结果
- 它负责把这两件事绑在一起

### 先看方法签名

```java
public static Flux<GraphResponse<StreamingOutput>> createStreamingGenerator(
    Class<? extends NodeAction> nodeClass,
    OverAllState state,
    Flux<ChatResponse> sourceFlux,
    Flux<ChatResponse> preFlux,
    Flux<ChatResponse> sufFlux,
    Function<String, Map<String, Object>> sourceMapper)
```

第一次看有点长，但其实只要拆成 4 块就好：

- `sourceFlux`
  模型真正吐出来的流
- `preFlux`
  正式内容前先给前端看的提示流
- `sufFlux`
  正式内容后再补上的提示流
- `sourceMapper`
  把最终完整文本转成要写回 state 的结果

### 方法核心片段

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

### 这段代码为什么很巧

它同时解决了两个看起来容易打架的需求。

#### 需求 1：前端要实时看到模型在输出什么

所以它必须保留流式 token。

#### 需求 2：节点结束后，Graph 还得拿到一个完整结构化结果写回 state

所以它又必须把整个输出拼起来。

这两个需求如果分开写，很容易每个 node 自己重复造一套逻辑。
`FluxUtil` 等于把这个套路抽成了统一模板。

### 它在仓库里最典型的使用方式

看这段：

```java
Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(
    this.getClass(),
    state,
    responseFlux,
    Flux.just(ChatResponseUtil.createResponse("正在进行意图识别..."),
        ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
    Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
        ChatResponseUtil.createResponse("\n意图识别完成。")),
    result -> {
        IntentRecognitionOutputDTO intentRecognitionOutput =
            jsonParseUtil.tryConvertToObject(result, IntentRecognitionOutputDTO.class);
        return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, intentRecognitionOutput);
    }
);
```

对应源码：
[IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)

你看这段就会发现，这个工具方法把一个节点最麻烦的 3 件事都接住了：

1. 前后提示语
2. 中间 token 流
3. 最终结构化回写

### 再看一个变体：`createStreamingGeneratorWithMessages(...)`

仓库里 Planner 常用的是这个变体。位置：
[PlannerNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

它更适合那种：

- 不想自己显式传前后两个 `Flux`
- 只想传开始提示和结束提示

### 你读 `FluxUtil` 时最该学的不是 API 名字，而是这种封装思路

也就是：

- 流式展示和最终状态回写，并不冲突
- 最好的做法不是每个节点自己手写一遍
- 而是抽一个统一包装层

这就是典型的成熟后端会做的抽象。

## 4. `JsonParseUtil.tryConvertToObjectInternal(...)`

源码位置：
[JsonParseUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/JsonParseUtil.java)

### 它的定位

这是结构化输出链路的最后一道保险。

我很建议你别把它当“小工具类”看。
在一个大量依赖 JSON 中间结果的 AI 项目里，它其实是稳定性核心的一部分。

### 方法核心片段

```java
String currentJson = removeThinkTags(json);

try {
    return parser.parse(objectMapper, currentJson);
}
catch (JsonProcessingException e) {
    log.warn("Initial parsing failed, preparing to call LLM: {}", e.getMessage());
}

for (int i = 0; i < MAX_RETRY_COUNT; i++) {
    try {
        currentJson = callLlmToFix(currentJson, ...);
        return parser.parse(objectMapper, currentJson);
    }
    catch (JsonProcessingException e) {
        ...
    }
}
```

### 这段逻辑其实很朴素，但很有效

#### 第一步，先清掉 `</think>` 之前的内容

```java
String currentJson = removeThinkTags(json);
```

这一步非常贴实战。

因为现在很多模型在结构化输出前，可能会先吐一段思考内容。
如果你直接拿整段去反序列化，肯定炸。

所以它先把这些噪音切掉。

#### 第二步，先正常解析

这很重要。
不要一上来就走“让模型修 JSON”这种重成本路径。

能一次过，就一次过。

#### 第三步，失败了再调用模型修

```java
currentJson = callLlmToFix(currentJson, errorMessage);
```

这里不是做复杂规则修补，而是直接让模型根据错误信息修一版新的 JSON。

这在 AI 项目里是很现实的取舍：

- 靠纯正则或手写修复规则，很难覆盖所有变形
- 让模型按报错信息回修，反而更稳

#### 第四步，重试上限明确

```java
private static final int MAX_RETRY_COUNT = 3;
```

这点也很重要。
兜底逻辑一定要有边界，不然就会变成无穷递归式自我修复。

### `callLlmToFix(...)` 还有两个细节值得看

#### 它会再次去掉 think 标签

```java
String cleanedJson = removeThinkTags(fixedJson);
```

说明作者默认认为：

- 修 JSON 的模型也可能继续输出思考内容

这是很接近真实模型表现的。

#### 它还会抽 Markdown 代码块里的原文

```java
cleanedJson = MarkdownParserUtil.extractRawText(cleanedJson);
```

因为模型很喜欢返回这种带代码围栏的内容：

` ```json { ... } ``` `

如果这里不清掉，还是没法直接反序列化。

### 这个工具真正解决了什么问题

不是“把 JSON 解析一下”这么简单。
它解决的是：

- 模型结构化输出不稳定
- 但业务链路又非常依赖结构化中间结果

所以它站在中间做了一个非常现实的缓冲层。

## 5. `McpServerService.listAgentsToolCallback(...)`

源码位置：
[McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)

### 它的定位

这是一个很典型的“不是所有 Tool 都必须走 Graph”的例子。

核心代码很短：

```java
@Tool(...)
public List<Agent> listAgentsToolCallback(AgentListRequest agentListRequest) {
    return agentMapper.findByConditions(agentListRequest.status(), agentListRequest.keyword());
}
```

### 这段代码值得学的点是什么

它提醒你一个很重要的工程判断：

- Tool 只是暴露方式
- 不等于所有能力都得套一层 Agent 工作流

像这种“查智能体列表”的能力，本来就是典型管理查询：

- 条件简单
- 不需要 Graph 编排
- 不需要流式过程

那就直接查库返回就好了。

这比“为了统一而统一走 Graph”更成熟。

## 6. `McpServerService.nl2SqlToolCallback(...)`

### 它的定位

这是另一个方向的典型例子：
当 Tool 背后本来就是图能力时，就让 Tool 调图。

核心代码：

```java
@Tool(...)
public String nl2SqlToolCallback(Nl2SqlRequest nl2SqlRequest) throws GraphRunnerException {
    Assert.hasText(nl2SqlRequest.agentId(), "AgentId cannot be empty");
    Assert.hasText(nl2SqlRequest.naturalQuery(), "Natural query cannot be empty");
    return graphService.nl2sql(nl2SqlRequest.naturalQuery(), nl2SqlRequest.agentId());
}
```

### 为什么这里不是走 `graphStreamProcess(...)`

因为 Tool 场景下，调用方通常关心的是“最终 SQL”，而不是“边想边输出的过程”。

所以这里走的是：

- [GraphServiceImpl.nl2sql(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

而 `nl2sql(...)` 里面又会走：

- `compiledGraph.invoke(...)`

这是一条很干净的能力暴露链：

Tool -> GraphService -> CompiledGraph.invoke -> 最终 SQL

### 这一段最值得学的设计点

同样是一个仓库能力，对不同调用方可以暴露不同入口：

- 前端页面要过程，就给 `stream(...)`
- Tool 调用要结果，就给 `invoke(...)`

这比“全都流式”或者“全都同步”更像正常系统。

## 7. `@Tool` 不是全部，真正的注册链要一起看

很多人第一次看 Spring AI Tool，会以为方法上标个 `@Tool` 就完事了。
在简单 demo 里这么理解还行，但这个仓库做得更完整。

### 先看方法标注

位置还是在：
[McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)

像这样：

```java
@Tool(description = "将自然语言查询转换为SQL语句...")
public String nl2SqlToolCallback(...) { ... }
```

这一步只是声明：

- 这个方法可以被当成工具

### 再看工具提供者怎么建

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

这里的意思是：

- 把 `McpServerService` 里的工具方法扫描出来
- 包装成 `ToolCallbackProvider`

### 再看为什么还要 `@McpServerTool`

这是为了和普通工具扫描分层。
仓库没有走“一股脑全扫描”的方式，而是专门给 MCP Server 工具打了标记。

### 最后看工具解析链怎么拼起来

位置在：
[DataAgentConfiguration.toolCallbackResolver(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

它里面会借助：
[McpServerToolUtil.excludeMcpServerTool(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/McpServerToolUtil.java)

核心思路是：

1. 先把 MCP Server 工具从默认扫描里排除掉
2. 再把静态工具和普通 Spring Bean 工具合并
3. 最后交给 `DelegatingToolCallbackResolver`

为什么要这么绕？

因为真实项目里，工具初始化顺序和模型初始化顺序很容易互相咬。
这个仓库在这里做的是“明确分层”，不是“碰运气地全扫描”。

## 8. 把这几类代码串起来看，逻辑就很顺了

你现在可以把这一圈基础设施按一条线记下来：

### 模型侧

- [DynamicModelFactory.createChatModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
  负责把配置变成真实模型
- [AiModelRegistry.getChatClient()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
  负责把当前生效模型统一拿出来

### 流包装侧

- [FluxUtil.createStreamingGenerator(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)
  负责把模型流变成 Graph 节点流，并顺手把最终结果写回 state

### 结构化稳定性侧

- [JsonParseUtil.tryConvertToObjectInternal(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/JsonParseUtil.java)
  负责在模型输出不标准时尽量救回来

### Tool 暴露侧

- [McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)
  负责把仓库能力变成方法级工具
- [McpServerConfig](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java)
  负责把这些工具注册成 `ToolCallbackProvider`
- [DataAgentConfiguration.toolCallbackResolver(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
  负责把工具真正接进 Spring AI 的解析链

## 9. 读这几类代码时，最容易混的几个点

### `AiModelRegistry` 和 `DynamicModelFactory`

- `DynamicModelFactory` 负责创建
- `AiModelRegistry` 负责缓存和拿当前生效实例

### `FluxUtil` 和 `GraphServiceImpl`

- `FluxUtil` 负责 node 内部的“模型流 -> 节点流”包装
- `GraphServiceImpl` 负责“节点流 -> SSE 流”桥接

### `JsonParseUtil` 和 `BeanOutputConverter`

- `BeanOutputConverter` 负责提前约束模型输出格式
- `JsonParseUtil` 负责事后兜底修复

### `@Tool` 和 `ToolCallbackProvider`

- `@Tool` 只是声明方法可作为工具
- `ToolCallbackProvider` 才是把工具真正组织进解析链

## 10. 建议下一步怎么读

- 想把主入口和恢复链接着读透，看 [请求入口、Graph 启动、人工反馈恢复](./request-entry-graph-service-methods.md)
- 想把整体 API 关系补齐，看 [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)
- 想把 Tool、多轮上下文、人工反馈串起来看，看 [MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖](../03-deep-dives/mcp-tool-multiturn-human-feedback-deep-dive.md)

## 11. `FluxUtil` 内部执行顺序

`createStreamingGeneratorWithMessages(...)` 可以拆成四段：

1. 对源 `Flux<ChatResponse>` 逐项提取文本。
2. 累加 token，构造带 nodeName 的 `StreamingOutput`。
3. 可选地在模型流前后拼接固定消息，例如 JSON 起止标记。
4. 源流完成后调用 result mapper，把完整文本转换为 state 更新。

所以返回值虽然放在节点 Map 的某个 key 下，框架消费它时得到的不只是文本，还会在最终完成时得到业务状态更新。

### `cascadeFlux(...)`

它用于“先消费第一段流，拿到累计结果后再创建第二段流”。这是一种串联，不是并行合并；第二段依赖第一段最终结果时应使用它，而不是 `merge`。

## 12. `JsonParseUtil` 的循环细节

```text
removeThinkTags
 -> Jackson parse
 -> 成功则返回
 -> 捕获解析异常
 -> callLlmToFix(json, error)
 -> 提取模型修复结果/Markdown 代码块
 -> 再次 removeThinkTags
 -> 下一轮解析
 -> 超过 MAX_RETRY_COUNT 抛出异常
```

修复模型接收原 JSON 和具体解析错误，使其知道缺少引号、括号还是字段格式错误。重试上限避免修复模型自身持续输出非法内容导致无限调用。

## 13. Registry 双重检查的目的

`getChatClient()` 和 `getEmbeddingModel()` 的典型思路是：

```text
先读 volatile 缓存
 -> 非空直接返回
 -> 为空进入 synchronized
 -> 再检查一次
 -> 查询激活配置
 -> 工厂创建
 -> 写缓存
```

第一次检查保证常规读取快；第二次检查防止多个线程都在锁外看到 null 后依次重复创建。

## 14. MCP NL2SQL 为什么使用 `invoke`

调用顺序：

```text
MCP 参数 JSON
 -> Nl2SqlRequest record
 -> Assert 校验
 -> GraphService.nl2sql
 -> compiledGraph.invoke(initialState)
 -> Optional<OverAllState>
 -> 读取 SQL_GENERATE_OUTPUT
 -> 返回字符串
```

该入口没有 SSE sink、StreamContext 和 Disposable，因为调用方只关心最终 SQL。它和页面共用 Graph 业务能力，但协议与生命周期完全不同。
