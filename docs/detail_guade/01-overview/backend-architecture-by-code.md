# 按源码拆架构：模块、配置、状态流、设计取舍

这篇不是再讲一遍“项目做了什么”，而是讲“这个仓库为什么要这样分层”。

## 1. 模块与依赖骨架

顶层 Maven 聚合在 [pom.xml](../../../pom.xml)，真正的后端模块是 [data-agent-management/pom.xml](../../../data-agent-management/pom.xml)。

从依赖能看出 6 个明确方向：

1. Spring Boot 3.4.x
2. Spring AI 1.1.x
3. Spring AI Alibaba Graph
4. WebFlux + SSE
5. MyBatis + 多数据库 JDBC
6. 向量库、MCP、OpenTelemetry、Docker 执行器

这说明它不是“单一聊天接口”，而是一个编排型后端。

## 2. 包结构怎么读

[data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent) 下可以按职责分 6 层：

### 2.1 `controller`

作用：

- 收 HTTP 请求
- 做参数接入
- 把执行权转给 service

重点不是 CRUD 控制器，而是：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

### 2.2 `config`

作用：

- 组装基础设施
- 定义 Graph
- 配模型、工具、HTTP Client、VectorStore、TextSplitter

最关键：

- [DataAgentConfiguration](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
- [McpServerConfig](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/McpServerConfig.java)

### 2.3 `service`

作用：

- 承接业务能力
- 封装模型调用、向量检索、数据库访问、会话管理、代码执行

主链最关键的 service：

- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)

当前激活 AI 模型的注册中心。

- [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- [AgentVectorStoreServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java)

### 2.4 `workflow/node`

作用：

- 真正执行业务节点逻辑

典型节点：

- [IntentRecognitionNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java)
- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
- [SqlGenerateNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java)

### 2.5 `workflow/dispatcher`

作用：

- 不做业务
- 只做跳转决策

这是这个仓库很有辨识度的设计。它把“节点做事”和“路由决策”拆开了。

### 2.6 `util` / `prompt` / `connector`

作用：

- `util` 负责公共桥接与清洗
- `prompt` 负责 PromptTemplate 和模板装配
- `connector` 负责多数据库访问细节

## 3. 图定义层和图执行层是两回事

这是最容易混淆的点。

### 图定义层

看 [DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)。

这一层做的是：

- `addNode(...)`
- `addEdge(...)`
- `addConditionalEdges(...)`
- 定义 `KeyStrategyFactory`

它回答的问题是：图长什么样。

### 图执行层

看 [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)。

这一层做的是：

- `stateGraph.compile(...)`
- `compiledGraph.stream(...)`
- `compiledGraph.invoke(...)`
- `compiledGraph.updateState(...)`

它回答的问题是：这次请求怎么跑图。

一句话记：

- `StateGraph` 是蓝图
- `CompiledGraph` 是发动机

## 4. state 才是这条链真正的“数据总线”

主链里很多值不是通过方法参数一级一级传，而是通过 `OverAllState` 在节点之间共享。

关键 state key 集中定义在常量中，实际合并策略定义在 [DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java) 的 `KeyStrategyFactory`。

重点 key：

- 输入类：`INPUT_KEY`、`AGENT_ID`、`MULTI_TURN_CONTEXT`
- 规划类：`PLANNER_NODE_OUTPUT`、`PLAN_CURRENT_STEP`、`PLAN_NEXT_NODE`
- SQL 类：`SQL_GENERATE_OUTPUT`、`SQL_REGENERATE_REASON`、`SQL_EXECUTE_NODE_OUTPUT`
- Python 类：`PYTHON_GENERATE_NODE_OUTPUT`、`PYTHON_EXECUTE_NODE_OUTPUT`
- 人工反馈类：`HUMAN_REVIEW_ENABLED`、`HUMAN_FEEDBACK_DATA`

读这个仓库时，只盯类名不够，一定要盯 state key 的来源、写入点、消费点。

## 5. 为什么 controller 很薄，service 和 workflow 很重

因为这个仓库不是传统 MVC 业务。

### MVC 风格

常见是：

- Controller 调 Service
- Service 调 Mapper
- 返回一个 JSON

### 这里的风格

- Controller 主要负责协议层
- Service 负责运行时协调
- workflow node 才是真正的步骤执行层

所以 [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java) 明显很薄，而 [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java) 和 `workflow/node` 很重。

## 6. Spring MVC 和 WebClient / RestClient 的区别，在这个仓库里怎么落地

这是用户最容易混的一个点。

### Spring MVC / WebFlux Controller

负责的是“别人请求我”。

这里的落点是：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

### `RestClientCustomizer`

负责的是“我作为客户端去请求别人”里的同步调用默认配置。

落点：

- [DataAgentConfiguration.restClientCustomizer(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

### `WebClient.Builder`

负责的是“我作为客户端去请求别人”里的异步/响应式调用默认配置。

落点：

- [DataAgentConfiguration.webClientBuilder(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
- [DynamicModelFactory.getProxiedWebClientBuilder(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)

一句话记：

- Controller 是服务端入口
- `RestClient` / `WebClient` 是出站客户端

## 7. Prompt 层为什么单独抽出来

看：

- [PromptConstant](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptConstant.java)
- [PromptHelper](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java)
- [resources/prompts](../../../data-agent-management/src/main/resources/prompts)

这样做的价值是：

- prompt 文本不散落在节点里
- 模板可复用
- 业务节点只关心“我要哪些参数”，不关心 prompt 文本怎么组织

这也是为什么这个项目里 `PromptTemplate` 和“纯字符串 prompt”要分开理解。

## 8. 你该从哪 2 个图理解整个仓库

先在脑子里保留这 2 张图：

### 8.1 运行路径图

`GraphController -> GraphServiceImpl -> CompiledGraph -> Node -> Dispatcher -> 下一节点`

### 8.2 数据路径图

`HTTP 参数 -> GraphRequest -> OverAllState -> 节点输出 -> SSE`

读懂这两张图，后面的类基本都能归位。

## 9. 建议连读

- 想先顺主链：看 [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)
- 想先学 API：看 [框架 API 代码级讲解总览](../02-api-code-guide/framework-api-code-level-guide.md)
- 想直接抠方法：看 [请求入口、Graph 启动、人工反馈恢复](../05-method-walkthrough/request-entry-graph-service-methods.md)

## 10. 分层之间的实际调用方向

```text
controller
  -> service/graph
  -> CompiledGraph
  -> workflow/node
  -> service/llm | service/vectorstore | service/datasource | service/code
  -> connector | mapper | 外部模型/数据库
```

反向数据返回则是：

```text
外部结果
  -> 节点返回 Map/Flux
  -> OverAllState 合并
  -> dispatcher 读取状态
  -> Graph 选择下一节点
  -> NodeOutput
  -> GraphServiceImpl
  -> SSE sink
  -> Controller Flux
```

### Spring Bean 装配顺序

应用启动后，Spring 先扫描 `@Configuration`、`@Service`、`@Component`。`DataAgentConfiguration` 负责构建 Graph、向量库、模型代理、HTTP 客户端和线程池；各 Node 作为 Spring Bean 被 `NodeBeanUtil` 获取并注册到 `StateGraph`。`GraphServiceImpl` 构造时拿到未编译的 `StateGraph`，再使用 `interruptBefore(HUMAN_FEEDBACK_NODE)` 编译为 `CompiledGraph`。

### 为什么 Node 不直接相互调用

节点之间通过 state 和边连接，而不是 `nodeA.call(nodeB)`。这样做的收益是：

- 跳转关系集中在图定义中，可视化和审查更容易。
- 节点只关心输入与输出，减少对其他节点实现的耦合。
- 重试、人工中断和条件分支可以由 dispatcher 统一控制。
- `CompiledGraph` 能保存 thread 对应的执行位置，用于恢复。

### 状态合并为什么几乎都是 `REPLACE`

节点返回的 Map 不是一份新 state，而是本轮增量更新。`KeyStrategyFactory` 决定同名 key 如何合并。项目大多使用 `REPLACE`，因为“当前 SQL、当前计划、当前步号”只应保留最新版；需要历史的数据由 `MultiTurnContextManager` 或步骤结果 Map 单独保存，避免 state 无限增长。
