# 框架 API 代码级讲解总览

这篇文档不只是“知道这个 API 叫啥”，而是要尽量帮你把下面几件事一次学明白：

1. 这个 API 在框架里扮演什么角色
2. 它平时应该怎么写
3. DataAgent 这个仓库到底是怎么落地它的
4. 你读源码时应该盯哪几个方法
5. 最容易踩的坑是什么

如果你前面已经看过 [总教程](../01-overview/total-tutorial.md) 和 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)，这篇会把里面出现的框架 API 全部“翻译成可上手的代码认知”。

## 0. 先记住一条总规律

这个仓库的大部分核心 API，不是孤立使用的，而是按下面这条链串起来的：

1. `ChatClient` / `ChatModel` / `EmbeddingModel`
   负责模型调用本身
2. `PromptTemplate` / `BeanOutputConverter` / `JsonParseUtil`
   负责把输入变成可控 prompt，再把输出变成可落地结构
3. `StateGraph` / `CompiledGraph` / `OverAllState` / `KeyStrategyFactory`
   负责把多个节点编排成一条可暂停、可恢复、可路由的工作流
4. `Flux` / `StreamingOutput` / `SSE`
   负责把执行过程实时推给前端
5. `@Tool` / `ToolCallbackProvider` / MCP
   负责把仓库能力暴露成可被模型调用的工具
6. `RestClientCustomizer` / `WebClient.Builder`
   负责模型 HTTP 通信和外部服务访问的底层客户端

你如果只记 API 定义，不记它在这条链上的位置，读代码还是会散。

## 1. `ChatClient`

### 它是什么

`ChatClient` 是 Spring AI 提供的高层调用门面。
你可以把它理解成一个“对话请求构造器 + 调用入口”。

它和 `ChatModel` 的关系很像：

- `ChatModel` 更底层，代表“模型能力本体”
- `ChatClient` 更上层，代表“我要怎么组织一次对话请求”

### 一般怎么用

最常见的同步思路：

```java
ChatClient chatClient = ChatClient.builder(chatModel).build();

String text = chatClient.prompt()
    .system("你是一个 SQL 专家")
    .user("帮我解释这条 SQL")
    .call()
    .content();
```

最常见的流式思路：

```java
Flux<ChatResponse> flux = chatClient.prompt()
    .system("你是一个分析助手")
    .user("请逐步分析这个问题")
    .stream()
    .chatResponse();
```

### 这个仓库里怎么用

真正的统一入口在 [AiModelRegistry.getChatClient()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)。

仓库没有让每个节点自己 `new ChatClient(...)`，而是：

1. 由 [DynamicModelFactory.createChatModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java) 先创建底层 `ChatModel`
2. 再由 [AiModelRegistry.getChatClient()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java) 包成 `ChatClient`
3. 各个节点通过 [StreamLlmService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java) 间接调用

仓库里的真实调用写法：

```java
return registry.getChatClient().prompt().user(user).stream().chatResponse();
```

对应源码：
[StreamLlmService.callUser(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)

### 你要注意什么

- `ChatClient` 不是模型实例，它是调用门面。
- 它特别适合业务层，因为业务更关心“怎么组织一次 prompt”，而不是底层 HTTP 协议。
- 这个仓库大量走的是 `stream().chatResponse()`，因为它的主链路是流式输出，不是一次性返回。

### 一句话记忆

`ChatClient` 解决的是“怎么发起一次对话请求”，不是“模型本体怎么实现”。

## 2. `ChatModel`

### 它是什么

`ChatModel` 是 Spring AI 的聊天模型抽象接口。
`ChatClient` 背后最终还是要靠它来真正调模型。

### 一般怎么用

你完全可以直接依赖 `ChatModel`：

```java
ChatModel chatModel = ...;
ChatClient chatClient = ChatClient.builder(chatModel).build();
```

或者某些更底层场景直接拿 `ChatModel` 调。

### 这个仓库里怎么用

仓库把它藏在了工厂和注册表后面：

- 创建位置： [DynamicModelFactory.createChatModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- 使用位置： [AiModelRegistry.getChatClient()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)

关键逻辑：

```java
ChatModel chatModel = modelFactory.createChatModel(config);
currentChatClient = ChatClient.builder(chatModel).build();
```

这个设计的重点不是“省一行代码”，而是：

1. 把模型供应商差异收敛到工厂
2. 把运行时缓存收敛到注册表
3. 让节点层只碰 `ChatClient`

### 你要注意什么

- 业务节点一般不直接依赖 `ChatModel`，因为那样会把底层模型切换逻辑暴露到业务层。
- 这个仓库支持动态模型切换，所以 `ChatModel` 是运行时创建的，不是写死的单例配置。

## 3. `EmbeddingModel`

### 它是什么

`EmbeddingModel` 负责把文本转成向量。
RAG 的入库和检索两边都会用到它。

### 一般怎么用

最简单的用法：

```java
EmbeddingModel embeddingModel = ...;
float[] vector = embeddingModel.embed("销售额同比下降的原因");
```

或者交给 `VectorStore` 间接使用。

### 这个仓库里怎么用

这个仓库有两个值得学的点：

1. 底层模型由 [DynamicModelFactory.createEmbeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java) 动态创建
2. 对外暴露的 Bean 不是固定实例，而是 [DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java) 里的动态代理

这段代理的意思是：

- Spring 容器里永远存在一个 `EmbeddingModel` Bean
- 但这个 Bean 每次真正被调用时，都会去 [AiModelRegistry.getEmbeddingModel()](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java) 拿“当前生效模型”

### 为什么要这样设计

因为很多 `VectorStore` Starter 在启动期就要求容器里存在 `EmbeddingModel`。
如果你只是想运行时切模型，但又不想破坏 Spring Boot 自动装配，这种“代理壳 + 动态目标”就很适合。

### 你要注意什么

- 这个仓库不是直接把 `EmbeddingModel` 注入后永久不变，而是做成“运行时可换芯”。
- 当没有配置有效 embedding 模型时，注册表会退回 Dummy 模型，目的是先让系统启动，而不是让 RAG 真能工作。

## 4. `PromptTemplate`

### 它是什么

`PromptTemplate` 是“带变量占位符的 prompt 模板”。
它解决的是 prompt 拼装问题，不负责模型调用。

### 一般怎么用

```java
PromptTemplate template = ...;
String finalPrompt = template.render(Map.of(
    "question", "本月销售额同比下降原因",
    "schema", "orders(order_id, amount, created_at)"
));
```

注意：

- 模板不是最终 prompt
- `render(...)` 后得到的字符串才是最终发给模型的内容

### 这个仓库里怎么用

模板主要来自 `resources/prompts`，拼装入口集中在：

- [PromptConstant](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptConstant.java)
- [PromptHelper](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java)

比如 Planner 的典型用法：

```java
String plannerPrompt = PromptConstant.getPlannerPromptTemplate().render(params);
```

对应源码：
[PlannerNode.handlePlanGenerate(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

### 你要注意什么

- “prompt 模板文件”和 `PromptTemplate` 不是一回事。前者是原材料，后者是框架对象。
- 真正重要的是参数怎么喂进去，而不是模板类名本身。

## 5. `BeanOutputConverter`

### 它是什么

`BeanOutputConverter<T>` 用来把“目标 Java 类型长什么样”翻译成模型能看懂的格式约束说明。

它不是 JSON 解析器。
它更像“结构化输出的提示词增强器”。

### 一般怎么用

```java
BeanOutputConverter<Plan> converter = new BeanOutputConverter<>(Plan.class);
String format = converter.getFormat();

String prompt = """
请输出执行计划。
输出必须满足如下格式：
%s
""".formatted(format);
```

### 这个仓库里怎么用

仓库里最典型的就是 Planner：

```java
BeanOutputConverter<Plan> beanOutputConverter = new BeanOutputConverter<>(Plan.class);
Map<String, Object> params = Map.of(
    "format", beanOutputConverter.getFormat()
);
String plannerPrompt = PromptConstant.getPlannerPromptTemplate().render(params);
```

对应源码：
[PlannerNode.handlePlanGenerate(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

另外意图识别、Query Enhance 这类结构化节点，也是在 prompt 阶段把目标 DTO 格式喂给模型。

### 它到底帮了什么

它帮你做的是：

1. 提前告诉模型你希望输出什么字段
2. 减少模型自由发挥
3. 提高 JSON 可解析概率

但它不保证 100% 成功。
所以这个仓库后面还接了 `JsonParseUtil` 做兜底。

### 你要注意什么

- `BeanOutputConverter` 负责“约束输出格式”
- `JsonParseUtil` 负责“解析失败后修复”

这两个 API 经常被初学者混成一个东西，其实它们一前一后，分工完全不同。

## 6. `JsonParseUtil`

### 它是什么

这是仓库自定义的 JSON 解析兜底工具。
它解决的是：模型明明“看起来像 JSON”，但就是不能稳定反序列化。

### 一般怎么用

思路通常是：

1. 先尝试正常 JSON 解析
2. 如果失败，做清洗、裁剪、提取
3. 还不行就再让模型帮忙修正

### 这个仓库里怎么用

典型位置：

- [IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)
- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [SqlExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java)

意图识别节点里的真实调用：

```java
IntentRecognitionOutputDTO intentRecognitionOutput =
    jsonParseUtil.tryConvertToObject(result, IntentRecognitionOutputDTO.class);
```

### 为什么这里必须有它

因为这个项目不是单纯聊天，而是大量依赖结构化中间结果：

- 意图识别 DTO
- Query Enhance DTO
- Plan
- 图表配置
- SQL 执行结果封装

只要中间结构不稳，整条 Graph 主链都会出问题。

### 你要注意什么

- 结构化输出不能只靠 prompt 约束，工程上一定要有解析兜底。
- 如果你只用了 `BeanOutputConverter`，但没做后置容错，系统还是很脆。

## 7. `VectorStore`

### 它是什么

`VectorStore` 是 Spring AI 的向量存储抽象。
你可以把它理解成“向量数据库统一接口”。

### 一般怎么用

它通常负责三件事：

1. 文档入库
2. 相似度检索
3. 删除或更新文档

### 这个仓库里怎么用

默认兜底实现配置在：
[DataAgentConfiguration.simpleVectorStore(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

核心思路：

```java
@ConditionalOnMissingBean(VectorStore.class)
public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

这代表：

- 本地开发时，没有外部向量库也能先跑起来
- 生产环境如果显式提供别的 `VectorStore`，这个默认实现自动失效

业务层又做了一层封装，入口在：
[AgentVectorStoreServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java)

### 你要注意什么

- 业务代码最好别到处直接操作底层 `VectorStore`，否则以后切换底层存储会很痛。
- 这个仓库把“向量库的技术细节”关在了 service 层，node 更多关注“召回结果怎么参与推理”。

## 8. `TextSplitter`

### 它是什么

`TextSplitter` 是知识入库前的文本切分器。

### 一般怎么用

长文档一般不会整篇直接 embedding，而是先切成 chunk：

```java
List<Document> chunks = textSplitter.apply(List.of(document));
```

### 这个仓库里怎么用

在 [DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java) 里一次性提供了五种切分器：

- `token`
- `recursive`
- `sentence`
- `semantic`
- `paragraph`

对应选择入口：
[TextSplitterFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/knowledge/TextSplitterFactory.java)

### 你要注意什么

- `TextSplitter` 发生在“知识入库侧”
- `EvidenceRecallNode` / `SchemaRecallNode` 发生在“知识召回侧”

这两层别混：

- 入库层解决“怎么切”
- 召回层解决“怎么查”

## 9. `StateGraph`

### 它是什么

`StateGraph` 是图的定义对象。
注意，是定义，不是执行。

你可以把它理解成一张“带状态共享能力的流程图 DSL”。

### 一般怎么用

最小思路一般是：

```java
KeyStrategyFactory keyStrategyFactory = () -> Map.of(
    "input", KeyStrategy.REPLACE,
    "result", KeyStrategy.REPLACE
);

StateGraph graph = new StateGraph("demo", keyStrategyFactory)
    .addNode("nodeA", nodeA)
    .addNode("nodeB", nodeB)
    .addEdge(StateGraph.START, "nodeA")
    .addEdge("nodeA", "nodeB")
    .addEdge("nodeB", StateGraph.END);
```

### 这个仓库里怎么用

最核心的定义在：
[DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

你读这个方法时，重点看三件事：

1. `addNode(...)`
   这决定图里有哪些业务节点
2. `addEdge(...)` / `addConditionalEdges(...)`
   这决定固定跳转和条件跳转
3. `KeyStrategyFactory`
   这决定同一个 state key 再次写入时怎么合并

### 你要注意什么

- `StateGraph` 只是“图纸”
- `CompiledGraph` 才是“真正能运行的机器”

## 10. `CompiledGraph`

### 它是什么

`CompiledGraph` 是 `StateGraph` 编译后的可执行对象。
如果说 `StateGraph` 是工作流定义阶段，那么 `CompiledGraph` 就是运行阶段。

### 这个仓库里怎么创建

创建位置在：
[GraphServiceImpl 构造器](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

真实代码：

```java
this.compiledGraph = stateGraph.compile(
    CompileConfig.builder()
        .interruptBefore(HUMAN_FEEDBACK_NODE)
        .build()
);
```

这个配置非常关键。它的含义是：

- 图跑到 `HUMAN_FEEDBACK_NODE` 之前先暂停
- 外部系统有机会把“人工审批结果”写回状态
- 然后再从暂停点恢复执行

这也是这个仓库支持 human-in-the-loop 的根本原因。

### `invoke(...)` 怎么用

`invoke(...)` 适合“一次跑完，只关心最终状态”的场景。

最小心智模型：

```java
OverAllState finalState = compiledGraph
    .invoke(
        Map.of("input", "查询近30天订单量", "agentId", "a-1"),
        RunnableConfig.builder().build()
    )
    .orElseThrow();
```

拿到最终状态后，你再从 state 里取结果：

```java
String sql = finalState.value("sql_generate_output", "");
```

这个仓库的真实用法在：
[GraphServiceImpl.nl2sql(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

真实代码就是：

```java
OverAllState state = compiledGraph
    .invoke(
        Map.of(IS_ONLY_NL2SQL, true, INPUT_KEY, naturalQuery, AGENT_ID, agentId),
        RunnableConfig.builder().build()
    )
    .orElseThrow();
return state.value(SQL_GENERATE_OUTPUT, "");
```

#### 什么时候该用 `invoke(...)`

- MCP 工具调用
- 只关心最终 SQL 或最终结果
- 不需要把中间过程推给前端

### `stream(...)` 怎么用

`stream(...)` 适合“一边执行，一边产出中间结果”的场景。

最小心智模型：

```java
Flux<NodeOutput> outputFlux = compiledGraph.stream(
    Map.of("input", "帮我分析销量异常", "agentId", "a-1"),
    RunnableConfig.builder().threadId("t-1").build()
);
```

然后你订阅这条流：

```java
outputFlux.subscribe(
    output -> { /* 处理每个节点输出 */ },
    error -> { /* 处理异常 */ },
    () -> { /* 处理完成 */ }
);
```

这个仓库的真实用法在：
[GraphServiceImpl.handleNewProcess(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

真实代码：

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

### `invoke(...)` 和 `stream(...)` 的区别到底是什么

这两个 API 很容易被说成一句“一个同步一个流式”，但这还不够。

真正要记住的是：

| 对比项 | `invoke(...)` | `stream(...)` |
|---|---|---|
| 返回值 | 最终 `OverAllState` | `Flux<NodeOutput>` |
| 关注点 | 最终结果 | 中间过程 + 最终完成 |
| 典型用途 | 工具调用、批处理、只要最终 SQL | SSE、前端过程展示、可观察执行 |
| 适合人工审批吗 | 不适合 | 适合 |
| 适合长链路吗 | 一般 | 很适合 |
| DataAgent 用途 | `nl2sql(...)` | `graphStreamProcess(...)` 主链 |

一句话说透：

- `invoke(...)` 是“把整张图当函数调用”
- `stream(...)` 是“把整张图当事件流执行”

### `updateState(...)` 怎么用

这是 `CompiledGraph` 另一个非常关键，但很多人第一次读会忽略的能力。

它的用途不是“普通更新状态”，而是“在已有线程上下文里修改状态并继续跑”。

这个仓库的人工反馈恢复，就是靠它实现的。

真实代码位置：
[GraphServiceImpl.handleHumanFeedback(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

核心代码：

```java
RunnableConfig baseConfig = RunnableConfig.builder()
    .threadId(threadId)
    .build();

RunnableConfig updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);

RunnableConfig resumeConfig = RunnableConfig.builder(updatedConfig)
    .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackData)
    .build();

Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
```

这里要学会三件事：

1. `threadId` 是恢复同一条图执行上下文的关键
2. `updateState(...)` 修改的是“这条 thread 对应的已保存状态”
3. 恢复执行时可以 `stream(null, resumeConfig)`，因为不是从头启动，而是从暂停点继续

### 一个最小 demo，帮你把 `invoke` / `stream` / `updateState` 连起来

```java
StateGraph graph = ...;
CompiledGraph compiled = graph.compile(
    CompileConfig.builder().interruptBefore("human_feedback").build()
);

String threadId = "demo-thread-1";

// 1. 第一次启动，流式执行
Flux<NodeOutput> firstRun = compiled.stream(
    Map.of("input", "分析近30天订单波动"),
    RunnableConfig.builder().threadId(threadId).build()
);

// 2. 图执行到 human_feedback 前暂停
// 3. 外部拿到人工反馈后更新状态
RunnableConfig updated = compiled.updateState(
    RunnableConfig.builder().threadId(threadId).build(),
    Map.of("human_feedback_data", Map.of("feedback", true, "feedback_content", "可以继续"))
);

// 4. 从暂停点恢复
Flux<NodeOutput> resumed = compiled.stream(null, updated);
```

### 你要注意什么

- 想恢复同一条执行链，必须把 `threadId` 带上。
- `invoke(...)` 更像“单次调用”，`stream(...)` 更像“会话执行”。
- `updateState(...)` 不是让 node 直接改状态，而是从图运行器外部恢复一条已暂停的执行链。

## 11. `OverAllState`

### 它是什么

`OverAllState` 是整条 Graph 的共享状态容器。
所有 node 和 dispatcher 基本都围着它转。

可以把它理解成：

- Graph 运行时的全局上下文
- 节点之间传递数据的唯一正规通道
- 每次节点执行时拿到的“当前快照”

### 一般怎么读

最常见的读法有两种。

第一种：直接读基础类型

```java
String input = state.value("input", "");
Boolean reviewEnabled = state.value("human_review_enabled", false);
```

第二种：先拿 `Optional`

```java
Optional<Object> value = state.value("planner_node_output");
```

第三种：结合仓库里的 `StateUtil` 做类型安全读取

```java
String query = StateUtil.getStringValue(state, INPUT_KEY);
Plan plan = StateUtil.getObjectValue(state, PLANNER_NODE_OUTPUT, Plan.class);
```

对应工具类：
[StateUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/StateUtil.java)

### 一般怎么写

这里是很多初学者最容易误会的点：

在这个框架里，节点通常不是直接 `state.put(...)`。
而是返回一个 `Map<String, Object>`，再由 Graph 按 `KeyStrategyFactory` 合并回状态。

也就是：

```java
@Override
public Map<String, Object> apply(OverAllState state) {
    String input = state.value("input", "");
    return Map.of("result", "处理后的结果");
}
```

### 这个仓库里怎么用

#### 例 1：IntentRecognitionNode 读取输入，写回识别结果

```java
String userInput = StateUtil.getStringValue(state, INPUT_KEY);
...
return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, generator);
```

对应源码：
[IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)

#### 例 2：PlannerNode 读取多份上游结果，写回计划流

```java
String semanticModel = (String) state.value(GENEGRATED_SEMANTIC_MODEL_PROMPT).orElse("");
SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
...
return Map.of(PLANNER_NODE_OUTPUT, generator);
```

对应源码：
[PlannerNode.handlePlanGenerate(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

#### 例 3：PlanExecutorNode 从 state 读计划，再写回下一跳节点

```java
Plan plan = PlanProcessUtil.getPlan(state);
...
return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
```

对应源码：
[PlanExecutorNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)

### `OverAllState` 在这个仓库里到底承载了哪些东西

你可以把 state 里的 key 分成 5 类：

1. 输入类
   例如 `INPUT_KEY`、`AGENT_ID`
2. 上下文类
   例如 `MULTI_TURN_CONTEXT`
3. 中间结构类
   例如 `INTENT_RECOGNITION_NODE_OUTPUT`、`TABLE_RELATION_OUTPUT`、`PLANNER_NODE_OUTPUT`
4. 执行控制类
   例如 `PLAN_CURRENT_STEP`、`PLAN_NEXT_NODE`、`PLAN_VALIDATION_STATUS`
5. 最终结果类
   例如 `RESULT`、`SQL_GENERATE_OUTPUT`

### 一个最小 demo，帮你理解 state 怎么在节点间流动

```java
public class NodeA implements NodeAction {
    @Override
    public Map<String, Object> apply(OverAllState state) {
        String input = state.value("input", "");
        return Map.of("normalized_query", input.trim().toLowerCase());
    }
}

public class NodeB implements NodeAction {
    @Override
    public Map<String, Object> apply(OverAllState state) {
        String query = state.value("normalized_query", "");
        return Map.of("result", "query=" + query);
    }
}
```

这里最重要的不是示例代码本身，而是这个心智模型：

- NodeA 不调用 NodeB
- NodeA 只写 state
- NodeB 只读 state
- Graph 决定谁先跑，谁后跑

### 你要注意什么

- 读这个仓库时，不要只追方法调用链，一定要追 state key。
- 一个节点的“真正输出”不一定是方法返回值本身，而是“它写回 state 的哪个 key”。
- 很多 node 返回的是 `generator`，也就是“流 + 最终状态回写”的组合体，这时更要同时看流输出和 state 输出。

## 12. `KeyStrategyFactory`

### 它是什么

`KeyStrategyFactory` 用来定义：
“同一个 state key 被再次写入时，应该怎么合并？”

这是 Graph 状态系统的合并规则中心。

### 一般怎么用

最小思路：

```java
KeyStrategyFactory keyStrategyFactory = () -> Map.of(
    "input", KeyStrategy.REPLACE,
    "messages", KeyStrategy.APPEND,
    "result", KeyStrategy.REPLACE
);
```

意思是：

- `input` 再次写入时，旧值被新值替换
- `messages` 再次写入时，可能走追加逻辑
- `result` 也走替换

### 这个仓库里怎么用

集中定义在：
[DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

这个仓库绝大多数 key 都是：

```java
keyStrategyHashMap.put(SOME_KEY, KeyStrategy.REPLACE);
```

这说明它的状态设计是“当前最新值优先”，而不是“完整历史累积优先”。

### 这意味着什么

比如：

- `PLAN_NEXT_NODE` 只关心当前下一跳
- `PLAN_VALIDATION_ERROR` 只关心当前这轮校验错误
- `SQL_GENERATE_OUTPUT` 只关心最新 SQL

这非常适合工作流执行，因为下游大多数时候需要的是“现在该怎么走”，不是“历史上每次怎么走过”。

### 你要注意什么

- 如果你误以为某个 key 会自动累积历史，很容易读错代码。
- 在这个仓库里，历史通常不靠 state 同 key 累积，而是通过别的结构保存，例如多轮上下文管理器、planner chunk 缓存等。

## 13. `Flux`

### 它是什么

`Flux<T>` 是 Reactor 里的“0 到 N 个异步结果流”。

### 一般怎么用

```java
Flux<String> flux = Flux.just("a", "b", "c");
flux.subscribe(System.out::println);
```

### 这个仓库里怎么用

你会看到三层不同的 `Flux`：

1. 模型层：`Flux<ChatResponse>`
   例如 [StreamLlmService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java)
2. Graph 层：`Flux<NodeOutput>` 或 `Flux<GraphResponse<StreamingOutput>>`
   例如 [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)、[FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)
3. SSE 层：`Flux<ServerSentEvent<GraphNodeResponse>>`
   例如 [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

### 你要注意什么

这三层千万别混。

一句话记忆：

- `Flux<ChatResponse>` 是模型吐 token
- `Flux<GraphResponse<StreamingOutput>>` 是节点把模型输出包装成图事件
- `Flux<ServerSentEvent<GraphNodeResponse>>` 是 HTTP 往前端推消息

## 14. `SSE`

### 它是什么

SSE 是 Server-Sent Events。
它是 HTTP 上的单向事件推送协议。

### 一般怎么用

后端持续返回 `text/event-stream`，前端用 `EventSource` 持续监听。

### 这个仓库里怎么用

入口在：
[GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

关键思路是：

1. 先建 `Sinks.Many<ServerSentEvent<GraphNodeResponse>>`
2. Graph 后台异步执行
3. 每来一个节点输出，就往 sink 里 `tryEmitNext(...)`
4. 前端收到持续 SSE 事件

### 你要注意什么

- SSE 不是 AI 框架，它只是传输层。
- 真正的业务执行还是发生在 `CompiledGraph.stream(...)` 和各个 node 里。

## 15. `@Tool`

### 它是什么

`@Tool` 用来声明一个 Java 方法可以作为工具暴露给模型。

### 一般怎么用

```java
@Tool(description = "根据自然语言生成 SQL")
public String nl2sqlToolCallback(String query) {
    ...
}
```

### 这个仓库里怎么用

核心位置：
[McpServerService](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/mcp/McpServerService.java)

仓库里把 DataAgent 的部分能力包装成工具，供 MCP 或其他工具调用链使用。

### 你要注意什么

- 标了 `@Tool` 不等于自动全局可用。
- 真正要让模型能找到它，还需要注册流程，也就是 `ToolCallbackProvider` / `ToolCallbackResolver`。

## 16. `ToolCallbackProvider`

### 它是什么

`ToolCallbackProvider` 用来把一组工具回调统一暴露给框架。

### 一般怎么用

思路通常是：

1. 收集带 `@Tool` 的方法
2. 组装成 `ToolCallback[]`
3. 提供给 Spring AI 的工具解析链

### 这个仓库里怎么用

相关位置：

- [McpServerConfig.mcpServerTools(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java)
- [DataAgentConfiguration.toolCallbackResolver(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
- [McpServerToolUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/McpServerToolUtil.java)

这个仓库的关键点不是“怎么注册一个工具”，而是“怎么把普通工具和 MCP 专用工具分层处理”。

### 为什么这里值得学

因为它没有简单做成“一股脑全扫描”，而是：

1. 先把 MCP 相关工具筛出来
2. 再把普通工具和工具提供者合并
3. 最后交给 `DelegatingToolCallbackResolver`

这能避免工具扫描和初始化顺序造成的副作用。

## 17. `RestClientCustomizer`

### 它是什么

`RestClientCustomizer` 是 Spring Boot 的同步 HTTP 客户端配置扩展点。

### 一般怎么用

最常见的用途：

- 配连接超时
- 配读超时
- 换请求工厂

### 这个仓库里怎么用

位置在：
[DataAgentConfiguration.restClientCustomizer(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

真实代码重点：

```java
return restClientBuilder -> restClientBuilder.requestFactory(
    ClientHttpRequestFactoryBuilder.reactor()
        .withCustomizer(factory -> {
            factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
            factory.setReadTimeout(Duration.ofSeconds(readTimeout));
        })
        .build()
);
```

### 它和 Spring MVC 是什么关系

这个 API 很容易被误会成 Web 入口配置，其实不是。

- Spring MVC / Controller 解决的是“别人怎么调用我”
- `RestClientCustomizer` 解决的是“我怎么去调用别人”

也就是：

- 它是出站客户端配置
- 不是入站 HTTP 控制器

## 18. `WebClient.Builder`

### 它是什么

`WebClient.Builder` 是响应式 HTTP 客户端构建器。

### 一般怎么用

```java
WebClient webClient = WebClient.builder()
    .baseUrl("https://api.example.com")
    .build();
```

### 这个仓库里怎么用

有两层用法。

第一层是默认基础配置：
[DataAgentConfiguration.webClientBuilder(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

```java
return WebClient.builder()
    .clientConnector(
        new ReactorClientHttpConnector(
            HttpClient.create().responseTimeout(Duration.ofSeconds(responseTimeout))
        )
    );
```

第二层是模型专用代理版：
[DynamicModelFactory.getProxiedWebClientBuilder(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)

这层会根据模型配置决定是否走代理。

### 它和 `RestClientCustomizer` 的区别

这也是高频混淆点。

| 对比项 | `RestClientCustomizer` | `WebClient.Builder` |
|---|---|---|
| 风格 | 同步 | 响应式 |
| 主要用途 | 配同步 HTTP 调用 | 配异步 / 响应式 HTTP 调用 |
| DataAgent 角色 | 默认同步客户端超时配置 | 模型异步流式调用底层客户端 |

一句话记忆：

- `RestClientCustomizer` 更像传统同步 HTTP 配置
- `WebClient.Builder` 更像 Reactor 体系下的异步 HTTP 配置

## 19. Graph API 最小实战串讲

如果你对 `StateGraph`、`CompiledGraph`、`OverAllState` 还是感觉抽象，下面这个最小案例最值得反复看。

### 定义图

```java
KeyStrategyFactory strategyFactory = () -> Map.of(
    "input", KeyStrategy.REPLACE,
    "normalized_query", KeyStrategy.REPLACE,
    "result", KeyStrategy.REPLACE
);

StateGraph graph = new StateGraph("demo-graph", strategyFactory)
    .addNode("normalize", state -> {
        String input = state.value("input", "");
        return Map.of("normalized_query", input.trim().toLowerCase());
    })
    .addNode("finish", state -> {
        String query = state.value("normalized_query", "");
        return Map.of("result", "final=" + query);
    })
    .addEdge(StateGraph.START, "normalize")
    .addEdge("normalize", "finish")
    .addEdge("finish", StateGraph.END);
```

### 编译图

```java
CompiledGraph compiledGraph = graph.compile();
```

### 一次性执行

```java
OverAllState state = compiledGraph.invoke(
    Map.of("input", "  Hello Graph  "),
    RunnableConfig.builder().build()
).orElseThrow();

String result = state.value("result", "");
```

### 流式执行

```java
Flux<NodeOutput> flux = compiledGraph.stream(
    Map.of("input", "  Hello Graph  "),
    RunnableConfig.builder().threadId("demo-1").build()
);
```

### 你从这个 demo 里应该学会什么

1. `StateGraph` 负责定义节点和边
2. `CompiledGraph` 负责执行
3. `OverAllState` 是节点共享上下文
4. 节点之间不直接互调，而是通过 state 传值
5. `invoke(...)` 拿最终状态，`stream(...)` 拿过程流

## 20. 最容易混的 8 组概念

### `ChatClient` 和 `ChatModel`

- `ChatModel` 是模型能力抽象
- `ChatClient` 是对话调用门面

### `PromptTemplate` 和 prompt 文本

- 前者是模板对象
- 后者是渲染后的最终字符串

### `BeanOutputConverter` 和 `JsonParseUtil`

- 前者负责提前约束结构
- 后者负责事后解析兜底

### `StateGraph` 和 `CompiledGraph`

- 前者定义图
- 后者执行图

### `OverAllState` 和节点返回 `Map`

- `OverAllState` 是当前共享状态快照
- 节点返回 `Map` 是“我要把哪些值写回状态”

### `invoke(...)` 和 `stream(...)`

- 前者关注最终态
- 后者关注执行过程

### 模型流、节点流、SSE 流

- 模型流：`Flux<ChatResponse>`
- 节点流：`Flux<GraphResponse<StreamingOutput>>` / `Flux<NodeOutput>`
- SSE 流：`Flux<ServerSentEvent<GraphNodeResponse>>`

### Spring MVC 和 `WebClient` / `RestClient`

- MVC 是入站
- `WebClient` / `RestClient` 是出站

## 21. 学完这篇后，下一步怎么读

- 如果你想继续把 Graph 彻底读透，接着看 [StateGraph / CompiledGraph / OverAllState 深挖](../03-deep-dives/stategraph-compiledgraph-overallstate-deep-dive.md)
- 如果你想继续把主链串起来，接着看 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)
- 如果你想继续看关键方法，接着看 [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)
- 如果你想继续看工具类与模型注册表，接着看 [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)

## 22. API 在本项目中的真实组合方式

这些 API 并不是各自独立使用，实际组合链如下：

```text
ModelConfigDataService
 -> AiModelRegistry
 -> DynamicModelFactory
 -> ChatModel
 -> ChatClient
 -> LlmService
 -> NodeAction.apply
 -> PromptTemplate
 -> Flux<ChatResponse>
 -> FluxUtil
 -> StreamingOutput
```

结构化输出链：

```text
BeanOutputConverter.getFormat()
 -> 格式要求进入 PromptTemplate
 -> 模型返回文本
 -> JsonParseUtil.tryConvertToObject
 -> DTO / Plan / DisplayStyleBO
 -> 节点写回 OverAllState
```

Graph 链：

```text
StateGraph.addNode/addEdge
 -> GraphServiceImpl 构造器 compile
 -> CompiledGraph.stream/invoke
 -> NodeAction.apply
 -> Map 状态更新
 -> KeyStrategyFactory 合并
 -> EdgeAction.apply
 -> 下一节点
```

### API 的同步与异步边界

| API | 调用形态 | 项目中的含义 |
|---|---|---|
| `CompiledGraph.invoke` | 阻塞式拿最终 state | MCP 的 NL2SQL 工具 |
| `CompiledGraph.stream` | 返回 `Flux<NodeOutput>` | 页面完整分析 |
| `LlmService.call...` | 返回 `Flux<ChatResponse>` | 模型增量输出 |
| `VectorStore.similaritySearch` | 检索调用 | RAG 召回 |
| `Sinks.Many.tryEmitNext` | 主动推送 | 图输出桥接到 SSE |
| `Disposable.dispose` | 取消订阅 | 浏览器断开后的资源清理 |

### 学 API 时要同时看三件事

不要只看方法签名。每个 API 都要确认：谁创建它、谁调用它、结果交给谁。例如 `ChatClient` 是 Registry 创建的，`LlmService` 使用它，Node 再使用 `LlmService`；节点不直接依赖底层模型实现。
