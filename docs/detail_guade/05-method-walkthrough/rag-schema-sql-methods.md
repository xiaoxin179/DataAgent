# IntentRecognition、RAG、Schema、SQL 主链：关键方法逐段讲解

这一篇讲的是从“用户问题进来”到“SQL 真正执行”之前，中间最关键的那条后端准备链。

顺着源码看，这条链大致是：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `SchemaRecallNode`
4. `TableRelationNode`
5. `SqlGenerateNode`
6. `SqlExecuteNode`

我建议你读的时候不要把这 6 个类当成 6 个孤立功能点，而要看成一个逐层收窄、逐层补上下文的过程：

- 先判断值不值得做
- 再补业务语义
- 再补数据库结构
- 再把结构整理成可执行 Schema
- 再按当前计划步骤生成 SQL
- 最后才把 SQL 落库执行

## 1. `IntentRecognitionNode.apply(...)`

源码位置：
[IntentRecognitionNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)

### 它在整条链里的定位

这是第一个真正有“大模型成本”的拦截点。

它要回答的问题不是：

- 这条 SQL 怎么写

而是更前置的问题：

- 这个输入值不值得进入后面的分析链

### 方法核心片段

```java
String userInput = StateUtil.getStringValue(state, INPUT_KEY);
String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(无)");
String prompt = PromptHelper.buildIntentRecognitionPrompt(multiTurn, userInput);

Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(
    this.getClass(),
    state,
    responseFlux,
    preFlux,
    sufFlux,
    result -> {
        IntentRecognitionOutputDTO intentRecognitionOutput =
            jsonParseUtil.tryConvertToObject(result, IntentRecognitionOutputDTO.class);
        return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, intentRecognitionOutput);
    }
);

return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, generator);
```

### 这一步为什么不能省

因为后面的链条很贵：

- 要调向量检索
- 要拉 Schema
- 要做表关系整理
- 要让模型规划
- 要生成和执行 SQL

不是所有输入都值得跑这么长一条链。
先挡住无关问题，是很正常的工程思路。

### 它读了哪些输入

- `INPUT_KEY`
  当前用户问题
- `MULTI_TURN_CONTEXT`
  历史上下文

这说明意图识别不是只看“这一句”，而是会考虑同 thread 里的历史背景。

### 它写回了什么

- `INTENT_RECOGNITION_NODE_OUTPUT`

这个 key 后面会被 dispatcher 消费，用来决定：

- 继续进 RAG 链
- 还是提前结束

### 为什么这里也走结构化输出

因为意图识别结果不是给用户看一句解释就完，而是后续系统要根据它做流程判断。
所以这里要的是 DTO，不是散文。

## 2. `EvidenceRecallNode.apply(...)`

源码位置：
[EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)

### 它在链路里的定位

这是“业务知识增强”的入口，不是数据库 Schema 增强。

它要解决的问题是：

- 用户问题里的业务术语、FAQ、领域知识，数据库自己解释不了

### 方法核心片段

```java
String prompt = PromptHelper.buildEvidenceQueryRewritePrompt(multiTurn, question);
Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(
    this.getClass(),
    state,
    responseFlux,
    ...,
    result -> {
        resultMap.putAll(getEvidences(result, agentId, evidenceDisplaySink));
        return resultMap;
    }
);
```

### 为什么这里先做 query rewrite

这点非常关键。

它不是直接拿用户原问题去查知识库，而是先让模型输出一个 `EvidenceQueryRewriteDTO`，再从里面拿：

- `standaloneQuery`

对应逻辑在：
[EvidenceRecallNode.extractStandaloneQuery(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)

原因很简单：

- 用户问题往往口语化、上下文化
- 直接拿去检索，不一定是最适合召回知识的表达

### 它实际召回哪两类知识

看 `retrieveDocuments(...)`：

```java
businessTermDocuments = vectorStoreService.getDocumentsForAgent(..., BUSINESS_TERM)
agentKnowledgeDocuments = vectorStoreService.getDocumentsForAgent(..., AGENT_KNOWLEDGE)
```

这说明它不是查一个大一统知识库，而是有意识地区分：

- 业务术语知识
- 智能体知识文档

### 为什么最后不直接把文档列表往下传

因为下游真正需要的不是“文档对象集合”，而是：

- 一个已经整理好的 evidence 文本

所以最后会：

```java
return Map.of(EVIDENCE, evidence);
```

这说明这一步已经把检索结果从“存储层对象”转换成了“prompt 层上下文”。

### 一句话总结

`EvidenceRecallNode` 的真正作用不是“查知识库”，而是先把业务语义补齐，再把它整理成后续节点能直接消费的 evidence。

## 3. `SchemaRecallNode.apply(...)`

源码位置：
[SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)

### 它在链路里的定位

这是数据库结构召回的第一层。

和 `EvidenceRecallNode` 不同，这一步不关心业务 FAQ，而关心：

- 哪些表相关
- 这些表里哪些字段相关

### 方法核心片段

```java
QueryEnhanceOutputDTO queryEnhanceOutputDTO = StateUtil.getObjectValue(state, QUERY_ENHANCE_NODE_OUTPUT, ...);
String input = queryEnhanceOutputDTO.getCanonicalQuery();
Integer datasourceId = agentDatasourceMapper.selectActiveDatasourceIdByAgentId(Long.valueOf(agentId));

List<Document> tableDocuments = new ArrayList<>(
    schemaService.getTableDocumentsByDatasource(datasourceId, input)
);
List<String> recalledTableNames = extractTableName(tableDocuments);
List<Document> columnDocuments =
    schemaService.getColumnDocumentsByTableName(datasourceId, recalledTableNames);
```

### 为什么这里先表后字段

这一点特别值得学。

如果直接全库字段检索，噪声会很大。
很多字段名词面上和问题相关，但所属表其实完全不对。

所以它先：

- 召回表

再：

- 基于命中的表召回字段

这就先做了一层范围收缩。

### 它为什么依赖 `QUERY_ENHANCE_NODE_OUTPUT`

因为 Schema 检索更适合吃：

- canonical query

而不是用户原始口语问题。
这也体现了前面 `QueryEnhanceNode` 的意义：把问题转成更适合检索和执行的表达。

### 没有活动数据源时为什么也返回 generator

看它处理 `datasourceId == null` 的逻辑，会返回一个带提示信息的 generator，而不是直接抛异常退出。

这一步的好处是：

- 前端仍然能收到统一格式的流式反馈
- Graph 主链的处理风格保持一致

这是一种“失败也按统一协议返回”的做法。

### 它写回了什么

- `TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT`
- `COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT`

注意，这里写回的还不是最终 SchemaDTO，只是候选文档。

## 4. `TableRelationNode.apply(...)`

源码位置：
[TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)

### 它在链路里的定位

这是从“召回文档”走到“可执行 Schema”的关键转换点。

如果没有这一步，前面拿到的还是一些零碎表文档和字段文档，下游 SQL 生成很难直接吃。

### 方法核心片段

```java
List<Document> tableDocuments = StateUtil.getDocumentList(state, TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT);
List<Document> columnDocuments = StateUtil.getDocumentList(state, COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT);
DbConfigBO agentDbConfig = databaseUtil.getAgentDbConfig(Long.valueOf(agentIdStr));
List<String> logicalForeignKeys = getLogicalForeignKeys(Long.valueOf(agentIdStr), tableDocuments);

SchemaDTO initialSchema = buildInitialSchema(...);

Flux<ChatResponse> schemaFlux = processSchemaSelection(
    initialSchema,
    canonicalQuery,
    evidence,
    state,
    agentDbConfig,
    result -> {
        resultMap.put(TABLE_RELATION_OUTPUT, result);
        ...
        resultMap.put(GENEGRATED_SEMANTIC_MODEL_PROMPT, semanticModelPrompt);
    }
);
```

### 这一步最关键的三件事

#### 第一，把文档恢复成 `SchemaDTO`

也就是先把候选表和字段重新组织成结构化 Schema。

#### 第二，补逻辑外键

这一步非常像真实企业数据环境。

很多业务库的物理外键不完整，单靠数据库元数据不够。
所以这里会额外从系统维护的逻辑关系里补 join 信息。

#### 第三，再用 `fineSelect(...)` 做一次精筛

这一步不是生成 SQL，而是在初始 Schema 基础上进一步缩噪。

这就说明作者并不满足于“召回到了就用”，而是会再筛一轮，保证下游 prompt 更干净。

### 它为什么还要生成 `GENEGRATED_SEMANTIC_MODEL_PROMPT`

因为这一步不只是决定保留哪些表，还会顺手把这些表相关的语义模型组织起来。

这样下游 `SqlGenerateNode` 在生成 SQL 时，就不只是拿数据库结构，还能拿到语义层补充。

### 一句话总结

`TableRelationNode` 是这条链里最关键的“结构化整理器”，它把召回碎片加工成真正可供 SQL 生成消费的 Schema 上下文。

## 5. `SqlGenerateNode.apply(...)`

源码位置：
[SqlGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)

### 它在链路里的定位

这是计划里的某一步真正开始落地成 SQL 的地方。

要特别注意，它不是直接按用户原问题生成 SQL，而是按：

- 当前执行步骤的 instruction

生成 SQL。

### 方法核心片段

```java
int count = state.value(SQL_GENERATE_COUNT, 0);
String promptForSql = getCurrentExecutionStepInstruction(state);
SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class, SqlRetryDto.empty());

if (retryDto.sqlExecuteFail()) {
    sqlFlux = handleRetryGenerateSql(...);
}
else if (retryDto.semanticFail()) {
    sqlFlux = handleRetryGenerateSql(...);
}
else {
    sqlFlux = handleGenerateSql(state, promptForSql);
}
```

### 这里最关键的不是“调模型出 SQL”，而是“分清当前是哪一类生成”

它至少要处理三种情况：

#### 第一种：首次生成

正常按当前步骤说明生成。

#### 第二种：SQL 执行失败后重生成

会把：

- 旧 SQL
- 执行错误

一起带回去重生成。

#### 第三种：语义一致性校验失败后重生成

这时虽然 SQL 能跑，但业务语义不对，也会走重生链。

### 为什么这一步要读 `SQL_REGENERATE_REASON`

因为 SQL 生成不是无状态的。
如果上一轮执行失败，或者语义校验没过，这些失败信息本身就是新一轮生成的重要上下文。

这比“失败了就重新随便生成一版”稳很多。

### `SQL_GENERATE_COUNT` 为什么也很重要

它控制最大重试次数。
一旦达到上限，会提前结束，而不是无限重生。

这个防护很有必要，不然 SQL 链一旦掉进坏循环，会一直打模型。

### 它最后写回了什么

主要包括：

- `SQL_GENERATE_OUTPUT`
- `SQL_GENERATE_COUNT`
- `SQL_REGENERATE_REASON`

这里 `SQL_GENERATE_OUTPUT` 的值最终是修剪后的 SQL 文本，而不是原始流。

## 6. `SqlExecuteNode.apply(...)`

源码位置：
[SqlExecuteNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java)

### 它在链路里的定位

这一步才是 SQL 真正落地到数据库执行的地方。

### 方法核心片段

```java
String sqlQuery = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
sqlQuery = nl2SqlService.sqlTrim(sqlQuery);

Long agentId = Long.valueOf(agentIdStr);
DbConfigBO dbConfig = databaseUtil.getAgentDbConfig(agentId);

return executeSqlQuery(state, currentStep, sqlQuery, dbConfig, agentId);
```

### 为什么它不直接自己写大段执行逻辑，而是拆到 `executeSqlQuery(...)`

因为 `apply(...)` 只负责拿好上下文、定位当前 agent 和 SQL。
真正的执行、结果包装、错误处理放到下一个方法里更清楚。

## 7. `SqlExecuteNode.executeSqlQuery(...)`

### 它在链路里的定位

这是 SQL 执行节点的真正核心。

### 方法核心片段

```java
ResultSetBO resultSetBO = dbAccessor.executeSqlAndReturnObject(dbConfig, dbQueryParameter);
DisplayStyleBO displayStyleBO = enrichResultSetWithChartConfig(state, resultSetBO);

Map<String, String> existingResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class, new HashMap<>());
Map<String, String> updatedResults = PlanProcessUtil.addStepResult(existingResults, currentStep, strResultSetJson);

result.putAll(Map.of(
    SQL_EXECUTE_NODE_OUTPUT, updatedResults,
    SQL_REGENERATE_REASON, SqlRetryDto.empty(),
    SQL_RESULT_LIST_MEMORY, resultSetBO.getData(),
    PLAN_CURRENT_STEP, currentStep + 1,
    SQL_GENERATE_COUNT, 0
));
```

### 先说成功路径里最重要的三件事

#### 第一，真正执行 SQL

通过 `dbAccessor.executeSqlAndReturnObject(...)` 落数据库。

#### 第二，把结果写进两份不同语义的状态

一份是：

- `SQL_EXECUTE_NODE_OUTPUT`

这更像步骤结果汇总表，后面报告节点会看。

另一份是：

- `SQL_RESULT_LIST_MEMORY`

这更像当前 SQL 结果集的内存态，后面 Python 节点会看。

#### 第三，推进当前步骤

```java
PLAN_CURRENT_STEP, currentStep + 1
```

这说明 SQL 执行成功后，不是整链结束，而是：

- 这一步完成了
- 回到执行器推进下一步

### 失败路径里最关键的是什么

```java
result.put(SQL_REGENERATE_REASON, SqlRetryDto.sqlExecute(errorMessage));
```

这一步非常重要。

它不是直接在这里决定“重试几次”，而是把失败原因写回 state，交给后面的 dispatcher 和 SQL 重生成链去消费。

这 again 体现了这个仓库的分层思路：

- node 负责把事实写清楚
- 后续图路由再决定下一跳

### `enrichResultSetWithChartConfig(...)` 为什么也在这里

这个方法不是 SQL 执行主流程的核心，但很值得一提。
它会根据结果样本，再让模型猜一个更合适的图表展示配置。

这说明：

- SQL 执行节点不只关心“查出来”
- 还顺手为展示层做了一点增强

但它是附加能力，不会反过来影响 SQL 主执行是否成功。

## 8. 这条链最值得学的设计点

### 第一，业务语义增强和数据库结构增强是分开的

先 `EvidenceRecallNode`，再 `SchemaRecallNode`。

### 第二，Schema 召回完还要再做结构化整理

不是检索到了文档就直接喂给 SQL 生成。

### 第三，SQL 生成是按计划步骤驱动的

不是直接拿原问题出 SQL。

### 第四，SQL 执行后的结果会同时服务后续步骤和最终报告

所以它会写两类不同语义的状态。

## 9. 建议接着读什么

- 想继续看 Planner 和执行器怎么接上这条链，看 [PlannerNode、PlanExecutorNode 与执行控制](./planner-plan-executor-methods.md)
- 想继续看 Python 和报告如何消费 SQL 结果，看 [Python 生成、执行、分析与报告链路](./python-report-methods.md)
- 想继续把 RAG 的大框架看透，看 [RAG / VectorStore / TextSplitter 深挖](../03-deep-dives/rag-vectorstore-textsplitter-deep-dive.md)

## 10. 节点输入输出与下一跳总表

| 节点 | 主要读取 | 主要写入 | 下一跳判断 |
|---|---|---|---|
| IntentRecognition | `INPUT_KEY`、`MULTI_TURN_CONTEXT` | `INTENT_RECOGNITION_NODE_OUTPUT` | 闲聊结束，否则 Evidence |
| EvidenceRecall | 问题、agent、历史 | `EVIDENCE` | 固定到 QueryEnhance |
| QueryEnhance | 问题、证据、历史 | `QUERY_ENHANCE_NODE_OUTPUT` | DTO 完整才进 Schema |
| SchemaRecall | 增强查询、agent | 表/字段 Documents | 有表才进 TableRelation |
| TableRelation | query、证据、Documents | Schema、dialect、语义 prompt | 成功、重试或结束 |
| Feasibility | query、Schema、证据 | 可行性文本 | 数据分析才进 Planner |
| SqlGenerate | Schema、证据、dialect、重试原因 | SQL、计数 | 校验、重试或结束 |
| SemanticConsistency | query、Schema、SQL | 布尔结果、重试原因 | 执行或重生成 |
| SqlExecute | SQL、agent、当前步 | 步骤结果、内存结果、下一步号 | 回计划或重生成 |

## 11. SQL 的三种生成上下文

首次生成时，`SqlGenerateNode` 使用标准问题、证据、Schema 和方言构造 `SqlGenerationDTO`，交给 `Nl2SqlService.generateSql(...)`。

语义不一致时，`SemanticConsistencyNode` 写入 `SqlRetryDto`，包含“生成的 SQL 与用户需求不一致”的原因。再次进入生成节点时，会携带旧 SQL 与校验建议。

数据库执行失败时，`SqlExecuteNode` 捕获异常并写入另一类 `SqlRetryDto`。生成节点这次会看到数据库真实错误，例如字段不存在或语法不兼容，从而有针对性地修复。

### 为什么重试原因要结构化

如果只保存一段错误字符串，生成节点无法稳定区分“SQL 执行失败”和“语义校验失败”。`SqlRetryDto` 把失败来源编码成明确状态，使不同 prompt 分支可控。

## 12. SQL 执行成功后的状态变化

```text
AccessorFactory/数据库访问器执行 SQL
 -> ResultSetBO
 -> 可选的图表配置增强
 -> 结果转成步骤展示文本
 -> SQL_EXECUTE_NODE_OUTPUT["step_N"] = 结果
 -> SQL_RESULT_LIST_MEMORY 保存 Python 可用的结构化行
 -> 当前 ExecutionStep.toolParameters.sqlQuery = 实际 SQL
 -> PLAN_CURRENT_STEP = N + 1
 -> SQL_GENERATE_COUNT = 0
 -> SQLExecutorDispatcher 返回 PLAN_EXECUTOR_NODE
```

同一份执行结果保存为两种形态，是因为报告需要可读文本，而 Python 需要结构化数据。一个字段同时承担两种语义会让后续处理非常脆弱。
