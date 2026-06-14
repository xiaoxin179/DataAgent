# 模型注册与动态模型切换深挖

这个专题最值得搞清楚的，不是“系统支持动态切模型”这句话本身，而是：

- 为什么切模型这件事没有散到业务节点里
- 为什么改配置后不需要重启应用
- 为什么聊天模型和 embedding 模型的处理方式还不完全一样

这三件事搞清楚了，你再看这个仓库的模型层，会觉得非常顺。

## 1. 先把角色分清：谁负责创建，谁负责生效，谁负责暴露

这条链里最重要的几个角色是：

1. `ModelConfigDataService`
   负责拿当前激活配置
2. [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
   负责把配置变成真实模型实例
3. [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
   负责缓存并暴露当前生效模型
4. [DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
   负责把 embedding 模型对外包装成稳定 Bean
5. `LlmService` / `VectorStore`
   负责在业务层真正消费这些模型

先记一句话：

- `DynamicModelFactory` 负责创建
- `AiModelRegistry` 负责当前生效实例

这句立住了，后面就不容易混。

## 2. `DynamicModelFactory` 为什么叫工厂，不叫注册中心

看 [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)。

它的职责很纯粹，就是把配置翻译成实例。

以 `createChatModel(...)` 为例，它会做：

- 校验 `baseUrl`、`apiKey`、`modelName`
- 构造 `OpenAiApi`
- 配同步 `RestClient`
- 配异步 `WebClient`
- 构造 `OpenAiChatOptions`
- 返回 `OpenAiChatModel`

以 `createEmbeddingModel(...)` 为例，也是一模一样的思路，只是最后产出的换成了 `OpenAiEmbeddingModel`。

### 它为什么适合叫工厂

因为它不关心：

- 当前哪个模型是激活的
- 这个实例是不是已经缓存过
- 什么时候该失效重建

它只关心：

- 你给我一份配置
- 我帮你造出一个模型实例

这就是典型工厂职责。

## 3. `AiModelRegistry` 为什么叫注册中心

看 [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)。

它处理的才是运行时“当前该用谁”的问题。

### `getChatClient()` 的核心逻辑

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

这里其实有三层意图：

#### 第一层，当前激活配置由数据层决定

也就是说，哪一个模型生效，不是靠代码写死，而是靠数据库配置决定。

#### 第二层，实例是懒加载的

不是启动时全造，而是第一次真用到才造。

#### 第三层，造出来后会缓存

这样平时请求不会每次都重新建。

### `refreshChat()` 和 `refreshEmbedding()` 为什么重要

这两个方法看起来很小，但它们决定了系统能不能“热切换”。

逻辑非常直接：

- 清空缓存
- 下次访问时再按最新配置重建

也就是说，模型切换的关键不在 Controller，不在 node，而在注册表缓存失效这一层。

## 4. 为什么这个仓库特别适合讲“统一 OpenAI 协议接入”

很多团队做模型接入时，容易把厂商差异散落到业务层。
这个仓库比较克制，几乎全收在工厂里了。

看 [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java) 你会发现：

- 上层业务统一只看 `ChatClient` / `EmbeddingModel`
- 中层注册表只关心“当前哪一个生效”
- 底层工厂统一用 OpenAI 协议做适配

这个设计最大的价值是：

- 新接一个兼容 OpenAI 协议的模型服务，业务层基本不用动

这对后续替换供应商、接私有模型服务都很友好。

## 5. `RestClientCustomizer` 和 `WebClient.Builder` 为什么两套都要有

这个点特别容易被讲得很虚，我这里直接说人话：

- 同步 HTTP 调用和响应式 HTTP 调用不是一套东西
- 模型调用链里两种都有可能用到

### 同步这一套

默认配置在：
[DataAgentConfiguration.restClientCustomizer(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

它解决的是：

- 连接超时
- 读超时
- 同步请求工厂配置

### 异步这一套

默认基础配置在：
[DataAgentConfiguration.webClientBuilder(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

模型代理配置在：
[DynamicModelFactory.getProxiedWebClientBuilder(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)

它解决的是：

- 流式调用
- Reactor / Netty 连接器
- 代理和响应超时

### 为什么不只保留一个

因为这个仓库里既有：

- 普通同步 HTTP 配置需求
- 模型流式调用需求

混成一个只会让边界更糊。

## 6. 聊天模型和 Embedding 模型，为什么处理方式不完全一样

这是这个专题里最值得单独想清楚的点。

### 聊天模型这条线

聊天模型最终对业务主要暴露的是：

- `ChatClient`

它的运行时创建逻辑在注册表里基本就能收住。

### Embedding 模型这条线

Embedding 模型会更麻烦，因为很多基础设施 Bean 在启动期就可能依赖它。

这就是为什么这个仓库给它又加了一层代理，位置在：
[DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

核心思想是：

- 对 Spring 容器来说，始终有一个稳定的 `EmbeddingModel` Bean
- 对真正业务调用来说，每次执行时再去注册表拿当前生效模型

这就解决了两个矛盾：

1. 启动时 Bean 依赖必须满足
2. 运行时模型又希望可切换

### DummyEmbeddingModel 又解决了什么

看 [AiModelRegistry.getEmbeddingModel()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)。

当没有有效 embedding 配置时，它不会直接给你 `null`，而是退一个 Dummy 模型。

目的不是让你误以为 embedding 还能正常用，而是：

- 先让依赖它的装配别在启动时直接炸
- 真到业务执行 embedding 时，再给出明确错误

这是一种典型的“启动期容忍，运行期显错”策略。

## 7. 为什么这个设计让业务节点几乎感知不到模型切换

你去看这些节点：

- [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [SqlGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)

它们关心的是：

- 这次 prompt 怎么拼
- 结果怎么解析

它们根本不关心：

- 背后是哪个模型商
- 有没有走代理
- 当前模型是不是刚切过

这就是模型层分层做得好的价值。

## 8. 这套设计真正解决了什么问题

不是“技术上可以换模型”这么简单。
它真正解决的是三个现实问题。

### 问题 1：模型配置变化频繁

有管理后台时，这几乎是常态。

### 问题 2：不同模型服务接入方式不一致

需要有个统一工厂把差异吃掉。

### 问题 3：业务层不该知道模型接入细节

否则 node 很快就会变脏。

所以这套设计背后的核心思想其实是：

- 模型是基础设施，不是业务对象

## 9. 你可以怎么顺着源码读这一层

我建议顺序是：

1. [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
   先看模型怎么被造出来
2. [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
   再看当前实例怎么生效
3. [DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
   最后看 embedding 为什么还要做代理

这样读最顺。

## 10. 建议接着读什么

- 想继续把聊天模型、调用门面、embedding 这三者关系看透，看 [ChatClient / ChatModel / EmbeddingModel 深挖](./chatclient-chatmodel-embeddingmodel-deep-dive.md)
- 想继续把注册表和工厂的方法级逻辑看透，看 [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)
- 想继续把 API 用法整体串起来，看 [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)

## 11. 一次模型切换如何真正生效

1. 管理接口修改数据库中的激活模型配置。
2. 配置服务调用 Registry 的刷新方法。
3. Registry 把对应的缓存引用清空。
4. 下一次节点调用 `LlmService`。
5. `LlmService` 获取 `ChatClient` 时进入 Registry。
6. Registry 查询当前激活配置。
7. `DynamicModelFactory` 校验 endpoint、apiKey、modelName 等基础字段。
8. 工厂根据代理配置构造同步 `RestClient` 与异步 `WebClient`。
9. 工厂创建新的 `ChatModel`。
10. Registry 用它构建并缓存 `ChatClient`。
11. 后续请求复用新实例，直到下一次刷新。

### 为什么同步与异步客户端都配置

模型 SDK 可能在非流式请求中使用同步客户端，在流式响应中使用异步客户端。如果只给其中一条链配置代理或超时，就会出现“普通调用正常、流式调用失败”这类很难排查的问题。工厂统一构建两套客户端，保证同一模型配置在两种调用模式下一致。

### 刷新不是立即中断旧请求

刷新替换的是后续获取到的模型引用。已经启动并持有旧 `ChatClient`/连接的请求通常会继续完成，这比强制中止在途流更稳定。新旧请求短时间并存是合理的过渡状态。
