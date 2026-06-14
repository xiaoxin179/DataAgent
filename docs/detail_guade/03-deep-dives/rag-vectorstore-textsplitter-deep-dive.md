# RAG / VectorStore / TextSplitter 深挖

这个仓库的 RAG，如果只讲成“它接了个向量库”，那基本等于没讲。

因为它真正做的不是一层检索，而是把“知识增强”拆成了几段：

1. 先补业务语义证据
2. 再补表和字段的 Schema 证据
3. 再把这些碎片整理成下游真正能用的 `SchemaDTO`

所以你看这套 RAG，不能只盯 `VectorStore`，而要一起看：

- `EvidenceRecallNode`
- `SchemaRecallNode`
- `TableRelationNode`
- `AgentVectorStoreServiceImpl`
- `TextSplitterFactory`

## 1. 这个仓库的 RAG 不是一层，而是两层半

我建议你把它理解成“两层召回 + 半层结构化整理”。

### 第一层：业务证据召回

入口在：
[EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)

它解决的问题是：

- 用户问题里有业务语义
- 这些语义未必能直接从数据库 Schema 看出来

比如：

- 核心用户
- GMV
- 活跃用户
- 复购

这些词很多时候不是“表名列名能直接解释”的。

所以第一层的目标是：

- 先把业务背景补给系统

### 第二层：Schema 召回

入口在：
[SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)

这层解决的问题是：

- 这个问题到底可能关联哪些表
- 这些表里可能涉及哪些字段

这就比业务证据更偏数据库结构本身。

### 半层：结构化整理

入口在：
[TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)

为什么我说它是“半层”？

因为它已经不完全是检索了。
它做的是把前面召回到的碎片再整理一遍，产出真正可供 SQL 生成消费的 `SchemaDTO`。

也就是说，到这里 RAG 才真正从“查到一些知识”走到“形成可执行上下文”。

## 2. 第一层：`EvidenceRecallNode` 不是直接查知识库

看 [EvidenceRecallNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)。

它的流程不是：

- 用户原问题 -> 直接向量检索

而是：

1. 先让模型重写 query
2. 拿重写后的 `standaloneQuery` 去召回知识
3. 再把召回结果整理成 evidence 文本

### 为什么要先 query rewrite

因为用户自然语言经常很口语化、上下文化。
这类问题如果直接拿去做知识检索，召回质量不一定稳定。

所以它先让模型输出一个 `EvidenceQueryRewriteDTO`，从里面取：

- `standaloneQuery`

对应源码也在 [EvidenceRecallNode.extractStandaloneQuery(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)。

### 它召回的是哪两类知识

看 `retrieveDocuments(...)`：

```java
vectorStoreService.getDocumentsForAgent(agentId, standaloneQuery, DocumentMetadataConstant.BUSINESS_TERM)
vectorStoreService.getDocumentsForAgent(agentId, standaloneQuery, DocumentMetadataConstant.AGENT_KNOWLEDGE)
```

也就是说，它不是查一个大杂烩知识库，而是明确分成：

- `business_term`
  偏业务术语和定义
- `agent_knowledge`
  偏该智能体自己的知识文档、FAQ、QA

### 为什么这个拆法比“一次全检索”更好

因为这两类知识其实不是同一类东西。

业务术语更像：

- 定义词典

Agent knowledge 更像：

- 说明文档
- FAQ
- 经验规则

它们的格式和价值都不一样，先分开取，再统一整理，效果通常会比一锅端更稳。

### 这层最后产出的是什么

不是文档列表直接往下传，而是一个整理好的 evidence 文本：

```java
return Map.of(EVIDENCE, evidence);
```

这说明这层的真正产物是：

- 可被下游 prompt 直接消费的业务语义补充

## 3. 第二层：`SchemaRecallNode` 为什么不是直接全库字段检索

看 [SchemaRecallNode.apply(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)。

它的思路很清楚：

1. 先从 `QueryEnhanceOutputDTO` 拿 canonical query
2. 找当前 agent 的活动数据源
3. 先召回相关表
4. 再根据表名补字段文档

对应关键代码：

```java
List<Document> tableDocuments = new ArrayList<>(
    schemaService.getTableDocumentsByDatasource(datasourceId, input)
);
List<String> recalledTableNames = extractTableName(tableDocuments);
List<Document> columnDocuments =
    schemaService.getColumnDocumentsByTableName(datasourceId, recalledTableNames);
```

### 为什么不直接全库字段检索

因为字段比表多得多，噪声会非常大。

直接全库字段检索很容易出现：

- 某个字段名和问题词面接近
- 但它所属的表其实完全无关

先表后字段，相当于先做一层范围收缩。

这一步对后续 SQL 生成非常关键，因为 prompt 里的 Schema 噪声少一大截。

### 这层输出写到了哪里

```java
TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT
COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT
```

这两个 key 不是最终可执行 Schema，而是“候选文档集合”。

也就是说，这层的职责到这里就结束了，不去做表关系推理，不去做 SQL 生成。

这正是它分层清楚的地方。

## 4. `TableRelationNode` 为什么算 RAG 体系的一部分

很多人讲 RAG 时，讲到向量检索就停了。
但在这个仓库里，真正让检索结果变得“可执行”的关键一步，是 `TableRelationNode`。

位置在：
[TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)

### 它到底做了什么

它做的事情可以概括成 4 步：

1. 根据表文档和字段文档构建初始 `SchemaDTO`
2. 补逻辑外键
3. 调 `Nl2SqlService.fineSelect(...)` 再做一轮精筛
4. 反查语义模型，生成 `GENEGRATED_SEMANTIC_MODEL_PROMPT`

### 为什么这里很关键

因为前面两层召回拿到的还是“文档片段”。
但 SQL 生成真正需要的是：

- 哪些表最终要保留
- 表之间怎么 join
- 当前数据库方言是什么
- 哪些业务语义模型该补进去

这一步把文档碎片重新加工成结构化 Schema，上下游的语义层次就完全不一样了。

### 逻辑外键为什么重要

看它的 `getLogicalForeignKeys(...)`。

这个仓库没有把 join 关系完全寄希望于数据库物理外键。
它还会把系统维护的逻辑关系补进来。

这非常贴近企业真实数据环境，因为很多业务库：

- 根本没有完整外键
- 或者有外键但不全

如果没有这一步，模型就算拿到表和字段，也未必知道怎么安全地 join。

### `fineSelect(...)` 为什么不是多余的一步

初始召回得到的表集合，往往还偏宽。
所以它又加了一步：

- 在初始 Schema 上做精筛

这一步的价值是：

- 把噪声表再压掉一轮
- 降低下游 SQL prompt 的复杂度

换句话说，它不是“查到了就直接用”，而是“先查，再筛，再结构化”。

这比很多只做一次检索的 RAG 工程更细。

## 5. `VectorStore` 在这里不是裸用

很多人提到向量检索，会自然想到 node 直接操作 `VectorStore`。
这个仓库不是这么干的。

真正面向业务的入口是：
[AgentVectorStoreServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java)

### 这层包装的价值是什么

它把这些细节都收起来了：

- 按 `agentId` 过滤
- 按文档类型过滤
- 纯向量检索还是混合检索
- 不同向量库实现差异

这样 node 层就只关心：

- 我现在需要召回哪类知识

而不需要关心底层向量库到底怎么筛。

### 默认向量库从哪来

默认配置在：
[DataAgentConfiguration.simpleVectorStore(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

也就是说，本地开发时即使没有外部向量库，系统也能先跑通。

但真正的业务语义，不是写死在 `SimpleVectorStore` 上，而是写在业务包装层上。
这就给后面切 Milvus、Elasticsearch、PgVector 之类留了空间。

## 6. `TextSplitter` 服务的是入库，不是召回

这一点特别容易混。

仓库里的切分器配置在：
[DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

选择入口在：
[TextSplitterFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/knowledge/TextSplitterFactory.java)

它提供了几类 splitter：

- `token`
- `recursive`
- `sentence`
- `semantic`
- `paragraph`

### 它们解决的是什么问题

不是“召回时怎么查”，而是“入库前怎么切”。

也就是说：

- `TextSplitter` 影响的是 chunk 质量
- chunk 质量影响的是 embedding 质量
- embedding 质量再影响召回质量

所以它虽然不在 workflow node 里出现，但它其实在更上游就影响了整个 RAG 效果。

### 为什么这点要专门强调

因为很多人会把 RAG 讲成“检索就是 RAG 的全部”。
实际上，入库策略往往对效果影响同样大。

这个仓库把切分器单独工厂化，本身就说明作者知道这不是一个随便的细节。

## 7. 知识入库和知识召回分别在哪层

为了防止概念混乱，我建议你把这两层明确拆开。

### 知识入库侧

偏 service / infra：

- `service/knowledge`
- `service/vectorstore`
- `TextSplitterFactory`
- `EmbeddingModel`

它们负责的是：

- 文档怎么切
- 怎么 embedding
- 怎么入向量库

### 知识召回侧

偏 workflow：

- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)
- [TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)

它们负责的是：

- 当前问题该取哪些知识
- 这些知识怎么整理成下游可用上下文

这个分层是很干净的。

## 8. 这套 RAG 设计真正解决了什么问题

不是“把知识库接进来了”这么简单。
它解决的是两个层次完全不同的问题。

### 问题 1：业务语义缺失

靠 `EvidenceRecallNode` 补。

### 问题 2：数据库结构上下文缺失

靠 `SchemaRecallNode` 和 `TableRelationNode` 补。

这两类缺失如果不拆开，最后很容易变成：

- 一大堆知识全塞给模型
- prompt 很长
- 噪声很大
- SQL 还不稳

所以这个仓库最值得学的，不是它用了向量库，而是它知道该把哪类知识放在哪一层补。

## 9. 建议接着读什么

- 想继续看这些节点的方法级拆解，看 [IntentRecognition、RAG、Schema、SQL 主链](../05-method-walkthrough/rag-schema-sql-methods.md)
- 想继续把 API 用法补齐，看 [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)
- 想继续把模型、向量、工厂层串起来看，看 [模型注册与动态模型切换深挖](./model-registry-dynamic-switching-deep-dive.md)

## 10. 从用户问题到可用 Schema 的调用顺序

```text
EvidenceRecallNode
 -> LLM 将多轮问题改写成独立问题和检索词
 -> JsonParseUtil 解析 EvidenceQueryRewriteDTO
 -> AgentVectorStoreService 检索业务术语
 -> AgentVectorStoreService 检索 Agent 知识
 -> 合并、去重、格式化为 EVIDENCE
 -> QueryEnhanceNode 生成 canonicalQuery/expandedQueries
 -> SchemaRecallNode 先召回表
 -> 再基于候选表召回字段
 -> TableRelationNode 将 Document 恢复成 SchemaDTO
 -> 补数据库关系和逻辑外键
 -> fineSelect 精筛
 -> 生成语义模型 prompt
```

### 为什么分两次检索

第一次检索解决业务词义，例如“GMV”代表什么；第二次解决数据库落点，例如哪个表和字段能表达 GMV。如果直接用原问题搜 Schema，业务别名与物理字段名不一致时召回质量会明显下降。

### Document 的两个身份

入库时，`Document` 是切分后的知识或 Schema 片段；召回后，它同时携带文本和 metadata。`TableRelationNode` 依赖 metadata 恢复表、列和关系，因此 metadata 不是辅助信息，而是结构重建的一部分。

### TextSplitter 的调用位置

TextSplitter 位于知识写入流程：

```text
上传/保存知识
 -> TextSplitterFactory 选择实现
 -> split 文本
 -> EmbeddingModel 生成向量
 -> VectorStore.add
```

查询时不会重新切分已入库文档，而是对查询生成 embedding 并相似度搜索。
