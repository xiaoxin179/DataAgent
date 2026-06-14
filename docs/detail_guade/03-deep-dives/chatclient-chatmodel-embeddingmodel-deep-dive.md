# ChatClient / ChatModel / EmbeddingModel 深挖

这三个名字经常一起出现，所以也最容易一起被讲混。

很多教程会告诉你：

- `ChatModel` 是模型
- `ChatClient` 是客户端
- `EmbeddingModel` 是向量模型

这当然不算错，但对于读这个仓库没太大帮助。
真正有用的问题是：

1. 这三个对象在 DataAgent 里是怎么串起来的
2. 为什么业务节点几乎不直接碰 `ChatModel`
3. 为什么 `EmbeddingModel` 还要再包一层动态代理

这篇就围着这三件事展开。

## 1. 先把调用链画出来

在这个仓库里，模型调用不是 node 直接 new 出来的，而是走了一条很清晰的链。

聊天模型这条链是：

1. [DynamicModelFactory.createChatModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
   根据数据库配置创建 `ChatModel`
2. [AiModelRegistry.getChatClient()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
   把 `ChatModel` 包成 `ChatClient` 并做缓存
3. [StreamLlmService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)
   统一暴露 `call(...)`、`callUser(...)`、`callSystem(...)`
4. 各个 workflow node
   只依赖 `LlmService`

向量模型这条链是：

1. [DynamicModelFactory.createEmbeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
   根据数据库配置创建真实 `EmbeddingModel`
2. [AiModelRegistry.getEmbeddingModel()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
   懒加载并缓存当前生效模型
3. [DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
   对外暴露一个动态代理版 `EmbeddingModel` Bean
4. `VectorStore`、`TextSplitter`、知识入库服务
   依赖这个统一代理对象

这两条链如果你先在脑子里立住，后面读代码会顺很多。

## 2. `ChatModel` 是底层能力，不是业务入口

先看 [DynamicModelFactory.createChatModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)。

它做的事情很朴素：

```java
OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
    .apiKey(apiKey)
    .baseUrl(config.getBaseUrl())
    .restClientBuilder(getProxiedRestClientBuilder(config))
    .webClientBuilder(getProxiedWebClientBuilder(config));

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

从这里你能看出两件事。

### 第一，它统一走 OpenAI 协议适配层

这个仓库没有把各家模型 SDK 直接散落到业务层，而是集中走 OpenAI 协议兼容接口。

这么做的好处非常实际：

- 新接一个兼容 OpenAI 协议的模型服务，改工厂就行
- 业务节点不用知道现在背后是阿里云、OpenAI 还是别的兼容服务
- 代理、路径覆盖、超时、鉴权都能集中收口

### 第二，它故意把 `ChatModel` 留在基础设施层

也就是说，这个仓库的设计意图很明显：

- `ChatModel` 负责“怎么跟模型服务说话”
- 业务层最好别直接依赖它

因为一旦业务层普遍直接拿 `ChatModel`，后面你就会遇到这些问题：

- 模型切换逻辑散到节点里
- prompt 组织风格散到节点里
- 同步 / 流式调用方式散到节点里

这就是为什么 `ChatModel` 在这里更像“发动机”，不是“方向盘”。

## 3. `ChatClient` 才是业务调用门面

继续看 [AiModelRegistry.getChatClient()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)。

核心代码：

```java
ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
ChatModel chatModel = modelFactory.createChatModel(config);
currentChatClient = ChatClient.builder(chatModel).build();
```

这里的意思很清楚：

- 先找当前生效配置
- 再创建底层 `ChatModel`
- 最后对业务暴露 `ChatClient`

### 为什么它不直接把 `ChatModel` 返回出去

因为业务层更关心的是“我怎么组织一次对话调用”，不是“底层模型接口有哪些方法”。

这个仓库里的真实调用都长这样：

```java
return registry.getChatClient()
    .prompt()
    .user(user)
    .stream()
    .chatResponse();
```

对应源码：
[StreamLlmService.callUser(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)

这里你就能看出 `ChatClient` 的真实价值了：

- 它把 prompt 构造链统一下来了
- 它把流式调用入口统一下来了
- 它让业务节点不用知道底层到底是什么具体 `ChatModel`

### 为什么这个仓库喜欢把 `ChatClient` 再包进 `LlmService`

这又是一个挺像成熟后端的选择。

因为它连 `ChatClient` 的使用方式都不想散到 node 里。
于是再往上一层，收成：

- `call(system, user)`
- `callUser(user)`
- `callSystem(system)`

这样 node 层能更专注于：

- Prompt 怎么拼
- 结果怎么解析

而不用每次都从 `prompt().system().user().stream()` 开始写一遍。

## 4. 为什么 node 不直接依赖 `ChatModel`

这个点值得单独拎出来讲，因为很多人第一次做 Spring AI 项目时，会自然地想“反正最后都是调模型，那我直接注入 `ChatModel` 不就行了”。

在小 demo 里，这么写问题不大。
但在这个仓库里，直接依赖 `ChatModel` 会带来几个明显问题。

### 问题 1：模型切换会变得很散

现在模型切换只需要关心：

- [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)

如果 node 层普遍直接拿 `ChatModel`，你很容易在业务层到处塞工厂或条件判断。

### 问题 2：调用风格没法统一

有的 node 适合：

- `system + user`

有的 node 适合：

- `user only`

有的 node 适合：

- 纯流式返回

这些风格如果不统一，很快就会变成每个 node 一套自己的调用姿势。

### 问题 3：后面想加埋点、监控、降级，会很难收口

把调用先收进 `LlmService`，再由 `LlmService` 统一走 `ChatClient`，会给后续改造留很大空间。

所以这个仓库在这一层的取舍其实很清晰：

- `ChatModel` 留给基础设施层
- `ChatClient` 给服务层
- `LlmService` 给业务层

## 5. `EmbeddingModel` 为什么更特殊

`EmbeddingModel` 和 `ChatModel` 的一个关键差别是：

- 聊天模型通常在真正发请求时才用到
- 向量模型有些时候在应用启动期就会被别的 Bean 依赖

这就导致它在动态切换场景下更难处理。

### 看这个仓库怎么做

位置在：
[DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

核心思路不是直接 return 一个固定的模型实例，而是：

```java
TargetSource targetSource = new TargetSource() {
    @Override
    public boolean isStatic() {
        return false;
    }

    @Override
    public Object getTarget() {
        return registry.getEmbeddingModel();
    }
};

ProxyFactory proxyFactory = new ProxyFactory();
proxyFactory.setTargetSource(targetSource);
proxyFactory.addInterface(EmbeddingModel.class);
return (EmbeddingModel) proxyFactory.getProxy();
```

### 这个代理到底解决了什么问题

它解决的是一个很现实的矛盾：

#### 矛盾 1：启动期必须有一个 `EmbeddingModel` Bean

像 `VectorStore` 这类基础设施 Bean，在装配时可能就会要求容器里有 `EmbeddingModel`。

#### 矛盾 2：运行期又希望能切换真实 embedding 模型

如果你直接把真实 `EmbeddingModel` 写死成一个实例，后面就不灵活。

所以这里给出的解法是：

- 对 Spring 容器来说，始终有一个稳定的 `EmbeddingModel` Bean
- 对业务调用来说，每次真正执行 embedding 时，都会去注册表拿当前生效模型

这就把“容器启动稳定性”和“运行时动态切换”两个诉求同时满足了。

## 6. `AiModelRegistry` 为什么是整个模型层的中枢

看 [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)。

这个类别被名字骗了，它不是那种“只存一份 map 的 registry”，它在运行时有三层职责。

### 第一层，拿当前激活配置

也就是：

- 当前聊天模型是哪一个
- 当前 embedding 模型是哪一个

### 第二层，懒加载实例

不是启动时全量初始化，而是第一次用到时才建。

### 第三层，缓存和失效

它支持：

- `refreshChat()`
- `refreshEmbedding()`

这意味着配置变更后，不用重启应用，只要失效缓存，下次访问就会拿新模型。

### 一个很容易忽略但很重要的细节

当 embedding 模型拿不到有效配置时，它不会简单返回 `null`，而是退回一个 DummyEmbeddingModel。

对应源码也在 [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java) 里。

这招的作用不是“让系统假装没问题”，而是：

- 先保证依赖 `EmbeddingModel` 的装配不至于直接炸掉
- 真到了业务执行 embedding 的时候，再抛出明确错误

这个处理很工程化。

## 7. 这三者和 RAG、Graph 的关系

讲到这里，你会发现这三个对象不是孤立 API。

### 对 Graph 来说

- `PlannerNode`、`IntentRecognitionNode`、`SqlGenerateNode` 这些节点，本质上都在用聊天模型
- 只是它们通过 `LlmService -> ChatClient` 间接调用

### 对 RAG 来说

- `VectorStore` 和知识入库流程依赖 `EmbeddingModel`
- 某些切分策略，例如语义切分，也会依赖 `EmbeddingModel`

所以你可以把它们分别理解成：

- `ChatClient / ChatModel` 服务于推理链
- `EmbeddingModel` 服务于知识链

## 8. 最容易讲错的几个点

### `ChatClient` 不是“更高级的模型”

它不是比 `ChatModel` 更强，而是更靠近业务调用。

### `EmbeddingModel` 不是只给向量库用

这个仓库里，语义切分器也可能用到它。
所以不要把它狭义理解成“只有检索时才会用”。

### 动态切换真正生效点不在 Controller

真正生效点在：

- [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
- [DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

## 9. 读这条链最好的顺序

如果你想彻底看明白这一层，建议按这个顺序读：

1. [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
   先搞清楚模型怎么被创建
2. [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
   再看模型怎么被缓存和拿出来
3. [DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
   再看为什么 embedding 要做代理
4. [StreamLlmService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)
   最后看业务层怎么消费

## 10. 建议接着读什么

- 想继续把模型切换这条线看透，看 [模型注册与动态模型切换深挖](./model-registry-dynamic-switching-deep-dive.md)
- 想继续把 API 用法补齐，看 [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)
- 想继续把模型注册表和工厂的关键方法逐段看透，看 [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)

## 11. 从节点调用到 HTTP 请求的完整链

```text
Node.apply
 -> LlmService.callUser/callSystem/call
 -> AiModelRegistry.getChatClient
 -> ChatClient.prompt(...)
 -> ChatModel
 -> OpenAI 协议客户端
 -> WebClient/RestClient
 -> 模型服务
 -> Flux<ChatResponse>
```

`ChatModel` 负责底层推理协议，`ChatClient` 负责 prompt、消息和调用门面，`LlmService` 再把项目常用调用方式统一起来。多包一层不是重复，而是把模型切换和节点业务隔离。

### Embedding 调用链

```text
VectorStore / TextSplitter
 -> Spring 注入的 EmbeddingModel 代理
 -> 代理每次调用 getTarget()
 -> AiModelRegistry.getEmbeddingModel()
 -> 当前激活的真实 EmbeddingModel
```

因此刷新 embedding 配置后，不需要重建所有依赖它的 Bean。代理仍是同一个 Spring Bean，但它每次把调用转给 Registry 当前持有的模型。

### 并发可见性

Registry 中当前模型字段使用 `volatile`，读线程能看到刷新后的引用；创建过程再通过同步控制避免多个线程重复初始化。`refreshChat()`/`refreshEmbedding()` 的本质是让缓存失效，下一次请求再按最新配置懒加载。
