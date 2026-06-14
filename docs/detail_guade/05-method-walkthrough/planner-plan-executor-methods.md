# PlannerNode、PlanExecutorNode 与执行控制：关键方法逐段讲解

如果说这个仓库最像“Agent 编排系统”的地方在哪，我会优先指给你看这两段：

- `PlannerNode`
- `PlanExecutorNode`

因为它们把“先做计划，再推进计划”这件事拆得很清楚。
很多项目直接让模型按用户问题出 SQL，当然也能跑，但走不远。这个仓库之所以能支持：

- 多步分析
- SQL / Python / 报告分流
- 人工审核
- 局部修计划

很大程度上就是因为它没有把“计划生成”和“执行推进”揉成一坨。

这篇就顺着这条线往下读。

## 先把这两层角色分清

### `PlannerNode`

负责：

- 根据问题、Schema、evidence、多轮上下文，产出一份结构化计划

它回答的问题是：

- 这件事该怎么做

### `PlanExecutorNode`

负责：

- 把计划读出来
- 校验计划
- 推进当前步
- 告诉系统下一跳该去哪

它回答的问题是：

- 现在该做计划里的哪一步

### `PlanExecutorDispatcher`

负责：

- 根据 `PlanExecutorNode` 写回的状态，决定图上的下一跳

它回答的问题是：

- 图应该跳到哪个节点

这三层千万别混。
它们正好对应：

- 计划生成
- 计划推进
- 节点跳转

## 1. `PlannerNode.apply(...)`

源码位置：
[PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)

### 它在整条链里的定位

它处在“上下文准备结束”和“真正执行开始”之间。

上游已经做完了这些事：

- 意图识别
- 业务证据召回
- Query Enhance
- Schema 召回
- 表关系整理
- 可行性判断

到 Planner 这里，问题已经不再是“有没有信息”，而是：

- 这些信息要怎么组织成一份执行计划

### 方法核心片段

```java
Boolean onlyNl2sql = state.value(IS_ONLY_NL2SQL, false);

Flux<ChatResponse> flux = onlyNl2sql ? handleNl2SqlOnly() : handlePlanGenerate(state);

Flux<ChatResponse> chatResponseFlux = Flux.concat(
    Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
    flux,
    Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()))
);

Flux<GraphResponse<StreamingOutput>> generator =
    FluxUtil.createStreamingGeneratorWithMessages(
        this.getClass(),
        state,
        v -> Map.of(PLANNER_NODE_OUTPUT, ...),
        chatResponseFlux
    );

return Map.of(PLANNER_NODE_OUTPUT, generator);
```

### 这段代码最值得先看懂什么

先看最后一行：

```java
return Map.of(PLANNER_NODE_OUTPUT, generator);
```

这说明 Planner 这个节点的真正输出，不是一段普通文本，而是：

- 一个流式 generator
- 且它最终会把计划写进 `PLANNER_NODE_OUTPUT`

所以后面下游消费的核心对象不是“某次对话回答”，而是：

- state 里的计划结果

### 它为什么先包一层 JSON 起止标记

```java
Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign()))
...
Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()))
```

原因有两个：

1. 前端知道这一段是结构化 JSON，不是普通说明文字
2. 后端在流结束时更容易拿到一个干净的结构化块

这一步在结构化输出节点里很常见，但放在 Planner 上尤其重要，因为后面 `PlanExecutorNode` 真的要消费它。

### `onlyNl2sql` 为什么要单独走分支

```java
Flux<ChatResponse> flux = onlyNl2sql ? handleNl2SqlOnly() : handlePlanGenerate(state);
```

因为这个仓库支持两种不同执行模式：

#### 普通模式

会让 Planner 真正生成一份多步计划，后面可能走 SQL、Python、报告。

#### `nl2sqlOnly` 模式

只需要一条简化计划，让后面直接走 SQL 生成就够了。

这可以减少不必要的规划成本，也让 Tool 场景更干净。

### 一句话总结

`PlannerNode.apply(...)` 做的事情是：
把已经准备好的上下文转成一份可流式展示、可写回 state、可被后续执行器消费的计划。

## 2. `PlannerNode.handlePlanGenerate(...)`

### 它在链路里的定位

`apply(...)` 更像外壳，真正拼 Planner prompt 和调模型的是这里。

### 方法核心片段

```java
String canonicalQuery = StateUtil.getCanonicalQuery(state);
String semanticModel = (String) state.value(GENEGRATED_SEMANTIC_MODEL_PROMPT).orElse("");
SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
String schemaStr = PromptHelper.buildMixMacSqlDbPrompt(schemaDTO, true);
String evidence = StateUtil.getStringValue(state, EVIDENCE);

BeanOutputConverter<Plan> beanOutputConverter = new BeanOutputConverter<>(Plan.class);
Map<String, Object> params = Map.of(
    "user_question", userPrompt,
    "schema", schemaStr,
    "evidence", evidence,
    "semantic_model", semanticModel,
    "plan_validation_error", formatValidationError(validationError),
    "format", beanOutputConverter.getFormat()
);

String plannerPrompt = PromptConstant.getPlannerPromptTemplate().render(params);
return llmService.callUser(plannerPrompt);
```

### 先看它读了哪些 state

这一步特别值得练“追 state key”的能力。

它主要读：

- `QUERY_ENHANCE_NODE_OUTPUT`
  间接拿 canonical query
- `TABLE_RELATION_OUTPUT`
  拿整理好的 SchemaDTO
- `EVIDENCE`
  拿业务知识补充
- `GENEGRATED_SEMANTIC_MODEL_PROMPT`
  拿语义模型补充
- `PLAN_VALIDATION_ERROR`
  如果前一版计划被打回，这里会拿反馈重新规划

也就是说，Planner 不是只看用户问题，它吃的是前面整条准备链积累下来的上下文。

### `BeanOutputConverter<Plan>` 在这里为什么特别关键

它的作用不是“解析 Plan”，而是：

- 约束模型按 `Plan` 结构输出

如果没有这层约束，Planner 很容易输出：

- 一堆自然语言建议
- 看起来像步骤，但不是稳定结构

那后面的 `PlanExecutorNode` 根本没法正常消费。

### `validationError` 为什么会回流到 Planner

看这段：

```java
String validationError = StateUtil.getStringValue(state, PLAN_VALIDATION_ERROR, null);
```

这意味着 Planner 不只是“第一次出计划”时会跑。
当 `PlanExecutorNode` 发现计划结构不合法、步骤不支持、参数缺失时，也会把错误写回 state，再让 Planner 带着这些错误信息重生成。

这就是这个仓库“计划可修”的关键基础。

### `buildUserPrompt(...)` 为什么要把旧计划也带进去

当存在 `validationError` 时，它会把：

- 原始问题
- 上一版被拒绝的计划
- 当前反馈信息

一起塞进新 prompt。

这不是重复啰嗦，而是为了让模型知道：

- 不是从头随便再给一版
- 而是基于上一版被拒绝的点做修复

这一步很贴近真实人机协作。

### 一句话总结

`handlePlanGenerate(...)` 是 Planner 的真正核心：
把问题、Schema、evidence、语义模型和历史反馈拼成一个强约束 prompt，要求模型输出结构化计划。

## 3. `PlanExecutorNode.apply(...)`

源码位置：
[PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)

### 它在整条链里的定位

这是整条执行链里最像“运行时调度中枢”的一个节点。

它不生成计划，不执行 SQL，不执行 Python。
它做的是：

- 解释计划
- 校验计划
- 决定当前应该执行哪一步

### 方法核心片段

```java
plan = PlanProcessUtil.getPlan(state);

if (!validateExecutionPlanStructure(plan)) { ... }
for (ExecutionStep step : plan.getExecutionPlan()) { ... }

Boolean humanReviewEnabled = state.value(HUMAN_REVIEW_ENABLED, false);
if (Boolean.TRUE.equals(humanReviewEnabled)) {
    return Map.of(PLAN_VALIDATION_STATUS, true, PLAN_NEXT_NODE, HUMAN_FEEDBACK_NODE);
}

int currentStep = PlanProcessUtil.getCurrentStepNumber(state);
List<ExecutionStep> executionPlan = plan.getExecutionPlan();

if (currentStep > executionPlan.size()) {
    return Map.of(...);
}

ExecutionStep executionStep = executionPlan.get(currentStep - 1);
String toolToUse = executionStep.getToolToUse();
return determineNextNode(toolToUse);
```

### 它先做的不是“跳转”，而是“校验”

这是很多人第一次看容易忽略的点。

在这个仓库里，计划不是一生成就默认可信。
它每次进入执行器时都会先校验。

这虽然看起来会多一点成本，但好处很大：

- 执行时拿到的是一份经过验证的计划
- 有问题可以及时退回 Planner 修
- 不会等到 SQL 或 Python 节点才发现上游计划结构有硬伤

### 校验分哪几层

#### 第一层：计划整体是否为空

```java
validateExecutionPlanStructure(plan)
```

也就是确认：

- plan 本身不空
- executionPlan 不空

#### 第二层：每一步是否合法

```java
for (ExecutionStep step : plan.getExecutionPlan()) {
    String validationResult = validateExecutionStep(step);
    ...
}
```

这里会检查：

- `toolToUse` 是否在白名单里
- `toolParameters` 是否存在
- SQL 步骤有没有 instruction
- Python 步骤有没有 instruction
- 报告步骤有没有 `summaryAndRecommendations`

这说明它校验的不是“模型有没有大概讲步骤”，而是“这份计划能不能被系统真正执行”。

### 为什么这里用白名单

看 `SUPPORTED_NODES`：

```java
Set.of(SQL_GENERATE_NODE, PYTHON_GENERATE_NODE, REPORT_GENERATOR_NODE)
```

这层白名单非常有必要，因为 Planner 本质上还是模型产出。
如果没有白名单保护，模型一旦写出一个系统根本不存在的节点名，整条链就会乱掉。

所以这里的设计很成熟：

- 计划可以来自模型
- 但系统执行权不能完全交给模型自由发挥

### `humanReviewEnabled` 为什么优先判断

```java
if (Boolean.TRUE.equals(humanReviewEnabled)) {
    return Map.of(PLAN_VALIDATION_STATUS, true, PLAN_NEXT_NODE, HUMAN_FEEDBACK_NODE);
}
```

这说明：

- 只要开启人工审核
- 计划一旦通过基本校验
- 就先别往下执行
- 先去人审

这一步把“计划生成”和“计划批准”明确拆开了。

### `currentStep` 为什么这么重要

```java
int currentStep = PlanProcessUtil.getCurrentStepNumber(state);
ExecutionStep executionStep = executionPlan.get(currentStep - 1);
```

这就是计划推进的核心。

它说明：

- 一份计划不是一次性全部执行
- 而是按当前步逐步推进

也正因为这样：

- SQL 执行完可以回到执行器推进下一步
- Python 分析完也可以回到执行器推进下一步

### 当 `currentStep > executionPlan.size()` 时为什么可能走报告

```java
return Map.of(
    PLAN_CURRENT_STEP, 1,
    PLAN_NEXT_NODE, isOnlyNl2Sql ? StateGraph.END : REPORT_GENERATOR_NODE,
    PLAN_VALIDATION_STATUS, true
);
```

这说明：

- 所有分析步骤跑完了以后
- 如果只是 `nl2sqlOnly`，可以直接结束
- 如果是完整分析链，还要再去报告节点做最终整理

这是执行链和输出链分层的一个体现。

### 一句话总结

`PlanExecutorNode.apply(...)` 做的事情是：
先验证这份计划能不能执行，再根据当前步决定系统此刻到底该走 SQL、Python、报告还是人工审核。

## 4. `PlanExecutorNode.determineNextNode(...)`

这个方法不长，但很值得看。

```java
if (SUPPORTED_NODES.contains(toolToUse)) {
    return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
}
else if (HUMAN_FEEDBACK_NODE.equals(toolToUse)) {
    return Map.of(PLAN_NEXT_NODE, toolToUse, PLAN_VALIDATION_STATUS, true);
}
else {
    return Map.of(PLAN_VALIDATION_STATUS, false, PLAN_VALIDATION_ERROR, ...);
}
```

### 它的意义不只是“分支判断”

它做的是一次安全兜底：

- 合法节点名才放行
- 不合法就回到校验失败路径

所以即使 Planner 某次写出了奇怪值，系统也不会直接往不存在的节点跳。

## 5. `buildValidationResult(...)` 为什么顺手累加 `PLAN_REPAIR_COUNT`

看这里：

```java
int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);
return Map.of(
    PLAN_VALIDATION_STATUS, false,
    PLAN_VALIDATION_ERROR, errorMessage,
    PLAN_REPAIR_COUNT, repairCount + 1
);
```

这一步的意义在于：

- 计划校验失败不只是报个错
- 还要给后面的 dispatcher 一个可量化的“已经修过几次”的状态

这样才能支持：

- 校验失败先回 Planner 修
- 修太多次就别死循环，直接结束

这就是 Graph 状态驱动的典型思路。

## 6. `PlanExecutorDispatcher.apply(...)`

源码位置：
[PlanExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/PlanExecutorDispatcher.java)

### 它在链路里的定位

它不是执行器本身，而是执行器的“路由解释器”。

也就是说：

- `PlanExecutorNode` 写状态
- `PlanExecutorDispatcher` 读状态并选下一跳

### 方法核心片段

```java
boolean validationPassed = StateUtil.getObjectValue(state, PLAN_VALIDATION_STATUS, Boolean.class, false);

if (validationPassed) {
    String nextNode = state.value(PLAN_NEXT_NODE, END);
    if ("END".equals(nextNode)) {
        return END;
    }
    return nextNode;
}

int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);
if (repairCount > MAX_REPAIR_ATTEMPTS) {
    return END;
}

return PLANNER_NODE;
```

### 这段代码的逻辑非常清楚

#### 情况 1：计划校验通过

直接按 `PLAN_NEXT_NODE` 去跳。

#### 情况 2：计划校验没通过，但还没修到上限

回 `PLANNER_NODE` 重新修计划。

#### 情况 3：已经修太多次

直接结束，避免死循环。

### 为什么 node 和 dispatcher 这样拆是合理的

如果把这些路由分支全写回 `PlanExecutorNode.apply(...)`，当然也能跑。
但它会把两个维度揉在一起：

- 计划是否合法
- 图下一跳去哪

现在拆开以后，逻辑层次清楚很多。

## 7. 这条设计链最值得学的点

### 第一，不直接执行用户问题，而是先生成计划

这让执行过程变得可见。

### 第二，不迷信 Planner，一定要做计划校验

这让执行过程变得更稳。

### 第三，不一次性跑完整份计划，而是按 `currentStep` 推进

这让执行过程变得可控。

### 第四，把业务判断和图跳转拆开

这让整个工作流更容易维护和扩展。

## 8. 建议接着读什么

- 想把入口到执行器这段接起来读，看 [请求入口、Graph 启动、人工反馈恢复](./request-entry-graph-service-methods.md)
- 想把 RAG 和 SQL 主链补齐，看 [IntentRecognition、RAG、Schema、SQL 主链](./rag-schema-sql-methods.md)
- 想把完整执行顺序串起来，看 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)

## 9. Planner 到执行节点的精确状态流

### Planner 输入

`handlePlanGenerate(...)` 读取：

- `StateUtil.getCanonicalQuery(state)`：增强后的标准问题，缺失时回退原问题。
- `PLAN_VALIDATION_ERROR`：上次计划为什么不合法。
- `GENEGRATED_SEMANTIC_MODEL_PROMPT`：语义模型说明。
- `TABLE_RELATION_OUTPUT`：结构化 Schema。
- `EVIDENCE`：业务知识证据。
- `PLANNER_NODE_OUTPUT`：修复计划时提供上一版计划。

这些内容进入 prompt 后，`LlmService.callUser(...)` 返回模型流。`apply(...)` 在流前后加 JSON 类型标记，再由 `FluxUtil.createStreamingGeneratorWithMessages` 输出 chunk，并在完成时去掉标记，将正文写入 `PLANNER_NODE_OUTPUT`。

### PlanExecutor 校验顺序

```text
读取 PLANNER_NODE_OUTPUT
 -> 解析 Plan
 -> plan/executionPlan 判空
 -> 校验每个 ExecutionStep
 -> 校验 toolToUse 是否在白名单
 -> humanReviewEnabled?
 -> 读取 currentStep
 -> 是否已超过总步骤
 -> 读取当前 ExecutionStep
 -> 写 PLAN_NEXT_NODE
```

白名单校验阻止模型随意生成不存在的节点名。模型负责提出计划，但真正可执行的能力边界由后端代码掌握。

### `currentStep` 的下标转换

业务步号从 1 开始，而 Java List 下标从 0 开始，所以当前步骤使用：

```java
executionPlan.get(currentStep - 1)
```

SQL 或 Python 步骤成功后写回 `PLAN_CURRENT_STEP = currentStep + 1`，再回到 `PlanExecutorNode`。这样执行器每次只处理一步，不需要在一个方法里循环执行整份计划。

### 校验失败修复循环

```text
PlanExecutorNode
 -> PLAN_VALIDATION_STATUS=false
 -> PLAN_VALIDATION_ERROR=具体原因
 -> PLAN_REPAIR_COUNT+1
 -> PlanExecutorDispatcher
 -> 未超限: PlannerNode
 -> Planner 读取旧计划和错误生成修正版
```

超过修复上限后 dispatcher 返回 `END`，防止模型在无效计划之间无限循环。

### 人工审核

开启人工审核时，第一次进入执行器会优先写 `PLAN_NEXT_NODE=HUMAN_FEEDBACK_NODE`，而不是立刻执行 SQL/Python。恢复后 `HumanFeedbackNode` 会关闭或保留审核开关，并决定继续执行还是回 Planner。
