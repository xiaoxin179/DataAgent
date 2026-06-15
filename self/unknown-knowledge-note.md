# 我暂时不懂但值得记住的知识

## 1. 可空值与包装

### 1.1 Optional 的本质
`Optional.of(x)` 不是把值变成普通 `Object`，而是把一个非空值包装成 `Optional<T>` 容器。

一句话记忆：
> `Optional` 不是结果本身，而是“装结果的盒子”。

## 2. 响应式流

### 2.1 Flux 的 `subscribe()`
`subscribe()` 可以先粗略理解成“开始消费这个 `Flux` 里的数据”。它不是普通的 `for` 循环，而是：
- 开始监听一个响应式流
- 流里每来一个元素，就自动回调你传进去的处理函数
- 还能分别处理 `error` 和 `complete`

一句话记忆：
> `subscribe()` 不是手动遍历集合，而是“订阅并接收流里的每个元素”。

## 3. 线程可见性

### 3.1 volatile 的作用
`volatile` 可以理解成“这个变量是大家共享的，谁改了，其他线程立刻能看到”。

它主要解决的是：
- **可见性**：一个线程修改后，另一个线程能尽快看到最新值
- **禁止部分重排序**：让关键操作不要被 JVM / CPU 随意重排

它不能解决的是：
- **原子性**：`count++` 这种复合操作依然不是线程安全的

常见使用场景：
- 一个线程写，多个线程读
- 状态标记，比如 `isRunning`、`cancelled`、`shutdown`
- 双重检查单例里的实例引用

一句话记忆：
> `volatile` = “让所有线程尽快看到同一个最新值”，但**不等于线程安全**。

## 4. Spring Boot 与常见注解

### 4.1 `@Slf4j`
Lombok 注解，用来自动生成 `log` 对象，省掉手写 `LoggerFactory.getLogger(...)`。

### 4.2 `@Configuration`
表示这个类是 Spring 配置类，用来声明 Bean 和配置项。

### 4.3 `@EnableAsync`
开启异步方法支持，标了 `@Async` 的方法才能真正异步执行。

### 4.4 `@EnableConfigurationProperties(...)`
把 `application.yml` / `application.properties` 里的配置绑定到对应的 `Properties` 类上。

### 4.5 `@Mock` 和 `@MockBean`
- `@Mock`：Mockito 创建的假对象，只在测试代码里生效
- `@MockBean`：Spring Boot 测试里替换容器中的真实 Bean

## 5. Spring AI Graph

### 5.1 `NodeAction`
Graph 里的节点执行标准接口。实现它的类才能作为图节点被调度。

### 5.2 `OverAllState`
图执行过程里的共享状态容器，节点之间通过它传递数据。

### 5.3 `StateGraph`
图工作流的总调度器，负责把节点、边、条件和状态串起来。

### 5.4 `getNodeBeanAsync()`
不是重新创建一个新 bean，而是把 Spring 容器里的普通节点包装成可异步调度的节点对象。

### 5.5 `KeyStrategyFactory`
定义 Graph 中各个 state key 的合并策略，决定是保留旧值还是用新值覆盖。

## 6. 以后继续补这里

后面如果再遇到看不懂的类、方法、语法、并发概念、框架机制，就继续补到这里。

整理原则：
- 先归类，再记细节
- 只记本质，不堆术语
- 一条口诀配一个核心概念
- 新知识优先补到原分类里，不够再扩展新小节
