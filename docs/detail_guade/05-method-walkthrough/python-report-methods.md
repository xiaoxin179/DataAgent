# Python 生成、执行、分析与报告链路：关键方法逐段讲解

如果说 SQL 链解决的是“把数据查出来”，那 Python 和 Report 这条链解决的是：

- 数据查出来以后，怎么继续做程序化分析
- 最后怎么把整个过程整理成人能读的结果

这个仓库没有把 SQL 当成终点，这一点很值得学。
因为很多真实分析问题，单条 SQL 查出结果以后，后面还会有：

- 二次统计
- 程序化清洗
- 聚合和转换
- 总结结论

这篇就顺着这条后半段链路往下看。

## 先抓主线

这条链的顺序大致是：

1. `PythonGenerateNode`
2. `PythonExecuteNode`
3. `PythonAnalyzeNode`
4. `ReportGeneratorNode`

你可以把它理解成四步：

- 先让模型写代码
- 再把代码真的跑起来
- 再把程序输出翻译成人话
- 最后把整条分析链整理成最终报告

## 1. `PythonGenerateNode.apply(...)`

源码位置：
[PythonGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonGenerateNode.java)

### 它在整条链里的定位

当前计划步骤如果判断更适合用程序式分析，而不是再补 SQL，就会来到这里。

换句话说，它负责的是：

- 让模型基于当前上下文写出一段待执行 Python 代码

它不执行代码，只生成代码。

### 方法核心片段

```java
SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
List<Map<String, String>> sqlResults = StateUtil.hasValue(state, SQL_RESULT_LIST_MEMORY)
    ? StateUtil.getListValue(state, SQL_RESULT_LIST_MEMORY) : new ArrayList<>();
boolean codeRunSuccess = StateUtil.getObjectValue(state, PYTHON_IS_SUCCESS, Boolean.class, true);
int triesCount = StateUtil.getObjectValue(state, PYTHON_TRIES_COUNT, Integer.class, 0);

ExecutionStep executionStep = PlanProcessUtil.getCurrentExecutionStep(state);
ExecutionStep.ToolParameters toolParameters = executionStep.getToolParameters();

String systemPrompt = PromptConstant.getPythonGeneratorPromptTemplate().render(Map.of(...));
Flux<ChatResponse> pythonGenerateFlux = llmService.call(systemPrompt, userPrompt);
```

### 它到底吃了哪些上下文

这一步不是只看一句“请写 Python”。
它吃进去的上下文很丰富：

- 当前可用的 `SchemaDTO`
- 前面 SQL 步骤产出的结果样本
- 当前计划步骤对 Python 工具的说明
- 代码执行资源限制

这说明 Python 生成不是一段孤立 AI 能力，而是接在整条分析链后面的。

### 为什么只给样例数据，不给完整结果集

看这个常量：

```java
private static final int SAMPLE_DATA_NUMBER = 5;
```

它只会把前几条样例结果塞进 prompt。
这么做的原因很实际：

- 完整结果集可能很大
- 全塞给模型，prompt 成本很高
- 对“如何写处理逻辑”来说，样例通常已经够了

这也是一个很像工程场景的取舍。

### 上一轮 Python 失败时，为什么要把旧代码和错误信息塞回 prompt

看这段：

```java
if (!codeRunSuccess) {
    String lastCode = StateUtil.getStringValue(state, PYTHON_GENERATE_NODE_OUTPUT);
    String lastError = StateUtil.getStringValue(state, PYTHON_EXECUTE_NODE_OUTPUT);
    userPrompt += ...
}
```

这一步很关键。
它说明 Python 重试不是“忘掉上一次，重新瞎写一份”，而是：

- 带着上一版代码
- 带着上一版错误
- 让模型基于失败上下文去修

这比盲目重生更容易收敛。

### 为什么系统 prompt 里还要写资源限制

它会把：

- Python 内存限制
- 超时时间

一起写进 prompt。

这说明作者不只是想让模型“能写代码”，而是希望模型从生成阶段就尽量写出更符合执行环境约束的代码。

### 一句话总结

`PythonGenerateNode.apply(...)` 做的事情是：
把当前步骤说明、Schema、SQL 结果样例和上轮失败信息整理成 prompt，让模型写出一段更可能跑通的 Python 代码。

## 2. `PythonExecuteNode.apply(...)`

源码位置：
[PythonExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonExecuteNode.java)

### 它在整条链里的定位

这里是 Python 代码真正落地执行的地方。

这一步和生成阶段最大的区别是：

- 生成阶段还在模型世界
- 执行阶段已经进入代码运行环境

### 方法核心片段

```java
String pythonCode = StateUtil.getStringValue(state, PYTHON_GENERATE_NODE_OUTPUT);
List<Map<String, String>> sqlResults = ...
int triesCount = StateUtil.getObjectValue(state, PYTHON_TRIES_COUNT, Integer.class, 0);

CodePoolExecutorService.TaskRequest taskRequest =
    new CodePoolExecutorService.TaskRequest(
        pythonCode,
        objectMapper.writeValueAsString(sqlResults),
        null
    );

CodePoolExecutorService.TaskResponse taskResponse = this.codePoolExecutor.runTask(taskRequest);
```

### 为什么要单独有 `CodePoolExecutorService`

因为这一步已经不是普通字符串处理了，而是：

- 执行用户问题驱动生成的代码

这种能力本身就应该被隔离在专门的执行服务里，而不是 node 自己乱搞。

这样做的好处是：

- 执行环境可控
- 后续可以切本地执行、Docker 执行、沙箱执行
- 运行错误、stdout、stderr 能统一处理

### 成功路径里最关键的两步

#### 第一步，执行代码

拿到：

- `stdOut`
- `stdErr`
- `exceptionMsg`

#### 第二步，尽量把 stdout 规范化

看这段：

```java
Object value = jsonParseUtil.tryConvertToObject(stdout, Object.class);
if (value != null) {
    stdout = objectMapper.writeValueAsString(value);
}
```

这说明 Python stdout 很多时候被期待成 JSON 或类似结构化结果。
这里会尽量把它重新整理成统一格式，方便后面继续消费。

### 失败路径最值得看的是什么

先看普通失败：

- 写 `PYTHON_EXECUTE_NODE_OUTPUT`
- 写 `PYTHON_IS_SUCCESS = false`

再看超过最大重试次数的失败：

```java
if (triesCount >= codeExecutorProperties.getPythonMaxTriesCount()) {
    return Map.of(
        PYTHON_EXECUTE_NODE_OUTPUT, fallbackGenerator
    );
}
```

这里不会继续无限回生成节点，而是进入：

- `PYTHON_FALLBACK_MODE = true`

这说明作者考虑得很现实：

- 代码链不可能永远重试
- 到上限以后要有一条可继续往下走的降级路径

### 一句话总结

`PythonExecuteNode.apply(...)` 的作用是：
把模型写出来的 Python 代码交给执行池真正跑起来，并把执行结果、错误状态和降级状态写回工作流。

## 3. `PythonAnalyzeNode.apply(...)`

源码位置：
[PythonAnalyzeNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PythonAnalyzeNode.java)

### 它在整条链里的定位

这一步不是再做数据分析本身，而是：

- 把 Python 原始输出翻译成人更容易看懂的分析结论

这点非常关键。
因为程序输出通常对机器友好，不一定对人友好。

### 方法核心片段

```java
String pythonOutput = StateUtil.getStringValue(state, PYTHON_EXECUTE_NODE_OUTPUT);
int currentStep = PlanProcessUtil.getCurrentStepNumber(state);
Map<String, String> sqlExecuteResult = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class, new HashMap<>());
boolean isFallbackMode = StateUtil.getObjectValue(state, PYTHON_FALLBACK_MODE, Boolean.class, false);
```

### 为什么它会去读 `SQL_EXECUTE_NODE_OUTPUT`

因为在这个仓库里，`SQL_EXECUTE_NODE_OUTPUT` 不只是 SQL 节点用，它逐渐被当成：

- 整个执行步骤结果的汇总表

Python 分析结果最后会以：

- `step_x_analysis`

这种 key 继续挂进去。

也就是说，到了后面报告节点，SQL 和 Python 结果会统一从这张结果汇总表里取。

### fallback 模式为什么直接给固定提示

看这段：

```java
if (isFallbackMode) {
    String fallbackMessage = "Python 高级分析功能暂时不可用，执行阶段出现错误。";
    ...
}
```

这说明作者不想让整条链因为 Python 不稳定而彻底断掉。
即使 Python 代码跑不出来，系统也可以：

- 明确告诉用户这一步降级了
- 但整条工作流还能继续往报告节点走

这在真实系统里比“直接整个分析失败”通常更可用。

### 正常模式下它怎么做

会把：

- 用户问题
- Python 输出

拼成一段 `pythonAnalyzePrompt`，再让模型产出解释性分析。

最后再把分析结果写回：

- `SQL_EXECUTE_NODE_OUTPUT`
- `PLAN_CURRENT_STEP = currentStep + 1`

也就是说，这一步做完以后，当前步骤才算真正完成，并推进到下一步。

### 一句话总结

`PythonAnalyzeNode.apply(...)` 的作用是：
把 Python 的机器输出翻译成用户可读的分析结论，并把结论挂回步骤结果汇总里。

## 4. `ReportGeneratorNode.apply(...)`

源码位置：
[ReportGeneratorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReportGeneratorNode.java)

### 它在整条链里的定位

这是整个多步分析链的收尾节点。

它不再查数据，也不再做代码执行。
它要做的是：

- 重新回看整份计划
- 汇总每一步结果
- 生成最终 Markdown 报告

### 方法核心片段

```java
String plannerNodeOutput = StateUtil.getStringValue(state, PLANNER_NODE_OUTPUT);
String userInput = StateUtil.getCanonicalQuery(state);
Integer currentStep = StateUtil.getObjectValue(state, PLAN_CURRENT_STEP, Integer.class, 1);
HashMap<String, String> executionResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, HashMap.class, new HashMap<>());

Plan plan = converter.convert(plannerNodeOutput);
ExecutionStep executionStep = getCurrentExecutionStep(plan, currentStep);
String summaryAndRecommendations = executionStep.getToolParameters().getSummaryAndRecommendations();

Flux<ChatResponse> reportGenerationFlux = generateReport(
    userInput,
    plan,
    executionResults,
    summaryAndRecommendations,
    agentId
);
```

### 为什么这里还要重新读 `PLANNER_NODE_OUTPUT`

因为最终报告不是简单地把最后一个结果往前端一丢就完了。
它需要知道：

- 用户原始需求是什么
- Planner 当时怎么想的
- 每一步原本打算用什么工具
- 每一步实际产出了什么

这些都必须回到计划本身去取。

### `SQL_EXECUTE_NODE_OUTPUT` 在这里为什么又出现了

因为到报告阶段，这个字段已经不只是“SQL 执行结果”了，而更像：

- 整个多步执行过程的结果汇总表

它里面可能有：

- step_1 的 SQL 结果
- step_2 的 SQL 结果
- step_2_analysis 的 Python 解释

报告节点会把这些重新组织起来。

### `summaryAndRecommendations` 从哪里来，为什么重要

它是从当前报告步骤的 `toolParameters` 里拿出来的。

也就是说，Planner 在计划阶段就已经为最终报告预留了：

- 总结方向
- 推荐重点

这说明报告不是事后胡乱生成，而是从计划阶段就考虑了。

### `generateReport(...)` 真正做了什么

看里面两步关键组织：

#### 第一步，构造“用户需求 + 计划概述”

方法是：

- `buildUserRequirementsAndPlan(...)`

#### 第二步，构造“各步骤执行结果”

方法是：

- `buildAnalysisStepsAndData(...)`

然后再结合：

- `summaryAndRecommendations`
- 可选的 Prompt 优化配置

一起生成最终报告 prompt。

### 为什么这里还接了 `UserPromptService`

因为报告风格和重点在企业场景里通常希望可配。
这里作者没有把报告 prompt 完全写死，而是留了：

- `promptConfigService.getOptimizationConfigs(...)`

这说明报告生成也被当成一个可持续调优的能力点，而不是一次性代码。

### 最后为什么要清一些中间状态

看这里：

```java
result.put(SQL_EXECUTE_NODE_OUTPUT, null);
result.put(PLAN_CURRENT_STEP, null);
result.put(PLANNER_NODE_OUTPUT, null);
```

这一步很有意思，它说明报告产出以后，作者不想把一堆中间状态继续挂在结果态里。

这有两个好处：

- 避免无关中间态继续污染后续流程
- 让最终结果更干净

### 一句话总结

`ReportGeneratorNode.apply(...)` 的作用是：
把计划、步骤结果和结论要求重新组织成最终面向用户的 Markdown 报告。

## 5. 这条 Python / Report 链最值得学的点

### 第一，代码生成和代码执行彻底分开

这让失败恢复逻辑更清楚。

### 第二，代码失败不是只能中断，也可以降级

这让整条分析链更稳。

### 第三，Python 分析结果会并回步骤结果汇总表

这让后面的报告节点能统一消费。

### 第四，报告不是简单模板拼接，而是基于计划回放整个执行过程

这让最终输出更像一份完整分析，而不是零散结果堆砌。

## 6. 建议接着读什么

- 想继续把计划生成和计划推进接起来看，看 [PlannerNode、PlanExecutorNode 与执行控制](./planner-plan-executor-methods.md)
- 想继续把 SQL 前半段链路补齐，看 [IntentRecognition、RAG、Schema、SQL 主链](./rag-schema-sql-methods.md)
- 想从整体视角再串一遍主链，看 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)

## 7. Python 分支的完整状态机

```text
PlanExecutorNode 选择 PYTHON_GENERATE_NODE
 -> PythonGenerateNode 读取 SQL_RESULT_LIST_MEMORY
 -> 只截取样例数据进入 prompt
 -> LLM 生成 Python 代码
 -> 写 PYTHON_GENERATE_NODE_OUTPUT
 -> PythonExecuteNode 交给 CodePoolExecutorService
 -> 成功: 标准化 stdout，PYTHON_IS_SUCCESS=true
 -> 失败: 保存错误，PYTHON_IS_SUCCESS=false，tries+1
 -> PythonExecutorDispatcher
    -> 可重试: PythonGenerateNode
    -> 成功/fallback: PythonAnalyzeNode
    -> 超限且不可降级: END
```

### 为什么生成节点读取上一次代码和错误

重试不是重新问同一个问题，而是把 `lastCode + lastError` 作为反馈，让模型做定向修复。否则每次生成彼此独立，重试次数增加但成功率不一定提高。

### 执行服务为什么抽象成工厂

`CodePoolExecutorServiceFactory` 可以根据配置选择本地、Docker 或模拟执行器。Node 只依赖统一执行契约，不关心进程创建、容器隔离、超时和内存限制的具体实现。

### stdout 标准化

Python 输出可能是 JSON，也可能是普通字符串。执行节点先尝试用 `JsonParseUtil` 解析为对象，再格式化输出；解析失败则保留原文本。这里的目标不是强迫所有脚本返回 JSON，而是尽可能给后续分析节点稳定输入。

## 8. 分析结果怎样进入最终报告

`PythonAnalyzeNode` 将分析文本写入 `SQL_EXECUTE_NODE_OUTPUT` 中类似 `step_N_analysis` 的 key，并推进 `PLAN_CURRENT_STEP`。因此 Report 节点只需要读取计划与这份步骤结果 Map，就能按计划顺序重放：

```text
Plan.executionPlan
 -> 对每一步计算 step_N key
 -> 取 SQL 执行结果
 -> 取可选 step_N_analysis
 -> 拼成 analysisStepsAndData
 -> LLM 生成最终报告
```

报告生成完成后清空计划、当前步和中间执行结果，是为了防止同一 thread 后续运行误读上一轮临时状态；多轮历史由独立的 ContextManager 保存，不依赖这些工作状态。
