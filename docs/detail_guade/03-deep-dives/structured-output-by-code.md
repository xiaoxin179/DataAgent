# Structured Output 深挖

这个仓库里，结构化输出不是一个“锦上添花”的小技巧，而是主链稳定性的基础设施。

因为它的很多关键节点都不是只要一段自然语言就够了，而是要把模型输出继续喂给下一段逻辑：

- 意图识别要变成 DTO
- Query Enhance 要变成 DTO
- Planner 要变成 `Plan`
- SQL 执行后图表配置要变成 `DisplayStyleBO`

这就意味着：
只要结构化输出不稳，后面的 Graph 节点就会跟着抖。

所以这个仓库做的不是“写个 JSON prompt 就完了”，而是两层保险：

1. 前置约束：尽量让模型按指定结构输出
2. 后置兜底：模型没完全听话时，再把结果修回来

## 1. 先把这条链讲清楚

在这个仓库里，Structured Output 的主链大致是：

1. `PromptTemplate` / `PromptHelper`
   负责把上下文和格式要求拼进 prompt
2. `BeanOutputConverter`
   负责告诉模型目标对象长什么样
3. `FluxUtil`
   负责一边流式展示，一边在结束时收完整文本
4. `JsonParseUtil`
   负责把结果转成 DTO，必要时做修复
5. node 把 DTO 写回 state

你如果把这几个环节当成独立小工具来看，会觉得零碎。
但把它们当成同一条“结构化结果生产线”，就清楚了。

## 2. 第一层：前置约束，不是靠运气让模型出 JSON

这层最重要的不是 `JsonParseUtil`，而是 `BeanOutputConverter`。

### 它到底在做什么

它不是负责解析 JSON。
它负责的是在 prompt 里明确告诉模型：

- 你输出的对象应该有什么字段
- 每个字段应该是什么结构

仓库里最典型的用法在 [PlannerNode.handlePlanGenerate(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)：

```java
BeanOutputConverter<Plan> beanOutputConverter = new BeanOutputConverter<>(Plan.class);
Map<String, Object> params = Map.of(
    "format", beanOutputConverter.getFormat()
);
String plannerPrompt = PromptConstant.getPlannerPromptTemplate().render(params);
```

这段代码真正做的事是：

- 把 `Plan.class` 变成模型能看懂的格式要求
- 再把这个格式要求塞进 Planner prompt

也就是说，它不是在“解析 Plan”，而是在“约束模型按 Plan 的样子输出”。

### 为什么这一步不能省

因为如果你只写一句“请返回 JSON”，模型的输出自由度其实很大：

- 字段名可能漂
- 层级可能乱
- 可能夹解释文字
- 可能多一层 code fence

结构化任务越复杂，这个问题越明显。

## 3. `PromptTemplate` 和 `BeanOutputConverter` 到底怎么配合

这是很多人第一次接触 Structured Output 时容易混的点。

### `PromptTemplate` 干什么

它负责把业务上下文拼进去。

比如：

- 用户问题
- Schema
- evidence
- semantic model
- 上轮错误反馈

### `BeanOutputConverter` 干什么

它负责把“目标对象的格式要求”拼进去。

所以对 Planner 来说，真正交给模型的 prompt 不是一个简单模板，而是：

- 业务上下文
- 目标格式约束

这两部分的组合。

## 4. 第二层：后置兜底，靠 `JsonParseUtil` 把输出救回来

前置约束很重要，但工程里不能指望它百分百成功。
所以这个仓库又做了第二层兜底。

核心类：
[JsonParseUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/JsonParseUtil.java)

先看它的核心流程：

```java
String currentJson = removeThinkTags(json);

try {
    return parser.parse(objectMapper, currentJson);
}
catch (JsonProcessingException e) {
    ...
}

for (int i = 0; i < MAX_RETRY_COUNT; i++) {
    currentJson = callLlmToFix(currentJson, ...);
    return parser.parse(objectMapper, currentJson);
}
```

### 这里的思路非常像实战

#### 第一步，先把明显噪音切掉

比如：

- `</think>` 之前的思考内容
- Markdown 包裹

如果这些不先清掉，后面根本没法正常反序列化。

#### 第二步，先按正常 JSON 解析

能一次成功就别走修复链。

#### 第三步，失败了再让模型修 JSON

这是这个仓库很实用的地方。
它不是试图写一大堆复杂规则去修所有畸形 JSON，而是直接借助模型自己修。

这种做法在 AI 项目里往往更划算，因为模型输出的畸形方式太多了，纯规则很难兜住。

### 为什么这层特别重要

因为这个仓库的结构化中间结果，不只是拿来展示，而是要继续推动主链往前走。

比如：

- 意图识别失败，后面可能根本不该进 RAG
- Planner 解析失败，PlanExecutor 就没法继续
- 图表配置解析失败，前端展示就会降级甚至报错

所以这不是“用户体验优化”，而是执行稳定性问题。

## 5. 哪些节点最依赖 Structured Output

### `IntentRecognitionNode`

位置：
[IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)

这一步会把模型输出转成 `IntentRecognitionOutputDTO`。

也就是说，这个节点不是“拿到一段回答就算完成”，而是必须拿到一个结构稳定的识别结果对象。

### `EvidenceRecallNode`

位置：
[EvidenceRecallNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)

这里第一段不是直接去召回知识，而是先让模型产出 `EvidenceQueryRewriteDTO`，再从里面拿 `standaloneQuery`。

如果这个 DTO 不稳，后面召回链就会歪。

### `QueryEnhanceNode`

虽然这里我们这篇不展开源码，但它也是典型结构化节点。
它的目标是得到 `QueryEnhanceOutputDTO`，后面 `SchemaRecallNode`、`PlannerNode` 都会依赖里面的 canonical query。

### `PlannerNode`

位置：
[PlannerNode.handlePlanGenerate(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

它要求模型给出 `Plan`，这是整条执行链里最关键的结构化输出之一。

### `SqlExecuteNode.enrichResultSetWithChartConfig(...)`

位置：
[SqlExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java)

这里会让模型根据样例结果数据输出图表配置，再解析成 `DisplayStyleBO`。

所以结构化输出不只出现在“计划和意图识别”，连结果展示增强也在用。

## 6. `FluxUtil` 在 Structured Output 里扮演什么角色

很多人讲 Structured Output 时，只讲 prompt 和 JSON 解析，不讲流式包装。
但在这个仓库里，流式包装恰好也很重要。

看这段：

```java
Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(
    this.getClass(),
    state,
    responseFlux,
    preFlux,
    sufFlux,
    result -> {
        IntentRecognitionOutputDTO dto =
            jsonParseUtil.tryConvertToObject(result, IntentRecognitionOutputDTO.class);
        return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, dto);
    }
);
```

对应源码：
[IntentRecognitionNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)

这里有个很值得学的设计：

- 前端可以边看过程输出
- 后端又能在流结束时拿到完整文本并做结构化解析

也就是说，Structured Output 和流式展示不是冲突关系。
关键在于中间要有像 `FluxUtil` 这样的桥接层。

## 7. 为什么 Planner 还要手动打 JSON 起止标记

看 [PlannerNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)：

```java
Flux<ChatResponse> chatResponseFlux = Flux.concat(
    Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
    flux,
    Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()))
);
```

这一步的意义不是“装饰一下输出”，而是给前后端都一个明确边界。

这样做的好处有两个：

### 第一，后端更容易识别结构化块范围

有些节点前后还会混着输出提示语。
如果没有明确边界，收尾时就容易把杂质一起算进 JSON 正文。

### 第二，前端知道现在收到的是 JSON 区块

这对展示层很有帮助，尤其是计划、图表配置这类天然适合结构化展示的内容。

## 8. `BeanOutputConverter` 和 `JsonParseUtil` 的关系，别再混了

这个点我单独强调一下，因为它实在太容易被混成一个概念。

### `BeanOutputConverter`

负责：

- 提前约束模型应该怎么输出

它发生在 prompt 阶段。

### `JsonParseUtil`

负责：

- 模型输出之后，尽量把文本稳定变成目标对象

它发生在解析阶段。

一句话记：

- 前者是“别乱写”
- 后者是“你就算乱写了，我尽量给你救回来”

## 9. 什么情况下一看就该怀疑 Structured Output

这个仓库里，如果你遇到下面这些现象，第一反应就该往 Structured Output 这层看：

- 节点流式输出看起来正常，但下游 state 拿不到对象
- 同一个节点偶发成功、偶发失败，失败日志里多是 JSON parse error
- Planner 明明吐出了计划文本，但 PlanExecutor 拿不到可解析的 Plan
- 图表配置偶发为空，前端退回默认展示

优先检查这几个位置：

- [PromptHelper](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java)
- [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
- [IntentRecognitionNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)
- [JsonParseUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/JsonParseUtil.java)

## 10. 这个仓库在 Structured Output 上最值得学的是什么

不是某一个类，而是它的整体思路：

### 它没有把“结构化”理解成只写一个 JSON prompt

而是拆成了：

- prompt 约束
- 流式边界标记
- 完整文本收集
- 解析兜底修复
- DTO 写回 state

### 它也没有把“解析失败”当成偶发小问题

而是给了解析失败一条正式的修复路径。

这就是为什么我会说，这一层不是技巧，而是基础设施。

## 11. 建议接着读什么

- 想继续把这层 API 用法看全，看 [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)
- 想继续看 `JsonParseUtil` 和 `FluxUtil` 的关键方法，看 [FluxUtil、JsonParseUtil、AiModelRegistry、DynamicModelFactory、MCP Tool](../05-method-walkthrough/utility-registry-mcp-methods.md)
- 想继续看结构化输出在主链里的真实落点，看 [IntentRecognition、RAG、Schema、SQL 主链](../05-method-walkthrough/rag-schema-sql-methods.md)

## 12. 结构化输出的完整生命周期

```text
DTO 类型
 -> BeanOutputConverter<DTO>
 -> converter.getFormat()
 -> 格式约束进入 prompt
 -> LLM 流式输出文本
 -> FluxUtil 累计完整字符串
 -> 去除 JSON 类型边界标记
 -> JsonParseUtil.tryConvertToObject
 -> Jackson 首次解析
 -> 失败时 LLM 修复 JSON
 -> 最多重试 3 次
 -> DTO 写回 state
```

### 为什么“流式展示”和“最终解析”能同时存在

`FluxUtil` 对每个 `ChatResponse` 一边产生 `StreamingOutput` 给前端，一边把文本追加进累加器。源 Flux 完成后，才调用 result mapper 处理完整字符串。因此用户能实时看内容，Graph 又能在节点结束时得到完整 DTO。

### 边界标记不属于业务 JSON

Planner 会输出 `TextType.JSON` 的起止标记。这些标记用于后端状态机和前端渲染，不能交给 Jackson。最终 result mapper 会去掉标记，只把中间 JSON 解析为 `Plan`。

### 解析失败的责任边界

- `BeanOutputConverter` 负责告诉模型“应该输出什么格式”。
- `JsonParseUtil` 负责处理模型“实际上输出了什么”。
- 节点负责决定解析失败后结束、降级还是重试。

三层职责分开后，prompt 约束、通用修复和业务决策不会混在一个方法里。
