# 1. Java 基础概念

## 1.1 可空值与包装

### 1.1.1 Optional 的本质

`Optional.of(x)` 不是把值变成普通 `Object`，而是把一个非空值包装成 `Optional<T>` 容器。

一句话记忆：
> `Optional` 不是结果本身，而是“装结果的盒子”。

## 1.2 函数式接口与 Lambda

### 1.2.1 函数式接口是什么

函数式接口就是“只有一个抽象方法的接口”。

这类接口天生适合配合 lambda 表达式使用，因为 lambda 本质上就是在给这个唯一的方法写实现。

比如：

```java
@FunctionalInterface
interface GreetingFactory {
    String apply(String name);
}
```

这里的 `apply(String name)` 就是唯一的抽象方法，所以它是函数式接口。

一句话记忆：
> 函数式接口就是“专门给 lambda 实现用的接口”。

### 1.2.2 Lambda 是什么

Lambda 可以理解成“匿名函数的简写写法”，它常用来直接实现函数式接口。

比如：

```java
GreetingFactory factory = name -> "你好，" + name;
```

这句代码的意思是：给 `GreetingFactory` 的 `apply(String name)` 方法提供一个实现。

如果不用 lambda，等价写法是：

```java
GreetingFactory factory = new GreetingFactory() {
    @Override
    public String apply(String name) {
        return "你好，" + name;
    }
};
```

所以可以把 lambda 记成：
- 写法更短
- 语义更清晰
- 专门用来实现函数式接口

一句话记忆：
> Lambda 就是“函数式接口的简写实现方式”。

### 1.2.3 静态引入是什么

静态引入就是把某个类里的 `static` 成员直接导入到当前文件里，这样使用时就可以省略类名前缀。

比如：

```java
import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
```

这句的意思是：把 `Constant` 类里的所有静态常量直接引进来，所以后面可以直接写：

```java
INPUT_KEY
RESULT
```

而不用写成：

```java
Constant.INPUT_KEY
Constant.RESULT
```

静态引入的主要作用是让代码更简洁，尤其适合集中写一大组常量映射的场景。不过如果引入太多，也会降低可读性，所以要适度使用。

一句话记忆：
> 静态引入就是“把静态成员直接搬到当前文件里用”，主要作用是简化书写。

# 2. 响应式编程

## 2.1 响应式流

### 2.1.1 Flux 的 `subscribe()`

`subscribe()` 可以先粗略理解成“开始消费这个 `Flux` 里的数据”，效果上有点像遍历，但它不是普通 `for` 循环。

它做的事情是：
- 开始监听一个响应式流
- 流里每发出一个元素，就自动回调你传进去的处理函数
- 同时还可以分别处理 `error` 和 `complete`

一句话记忆：
> `subscribe()` 不是手动遍历集合，而是“订阅并接收流里的每个元素”。

# 3. Spring Boot 与 Lombok

## 3.1 常用注解

### 3.1.1 `@Slf4j`

这是 Lombok 提供的注解，用来自动给当前类生成一个日志对象。

写了它之后，类里就可以直接使用：

```java
log.info("开始执行");
log.error("执行失败", e);
```

不用自己手写：

```java
private static final Logger log = LoggerFactory.getLogger(当前类.class);
```

一句话记忆：
> `@Slf4j` 就是“自动帮这个类准备好 log 日志工具”。

### 3.1.2 `@Configuration`

这是 Spring 的注解，表示这个类是一个配置类。

配置类的作用是告诉 Spring：
- 这个类不是普通业务类
- 里面可能会定义一些 Bean
- Spring 启动时要读取这里的配置

一句话记忆：
> `@Configuration` 就是“告诉 Spring：这个类是配置说明书”。

### 3.1.3 `@EnableAsync`

这是 Spring 的注解，用来开启异步方法支持。

开启后，项目里标了 `@Async` 的方法就可以异步执行。也就是调用方不用一直等这个方法执行完，可以先继续往下走。

常见场景：
- 发邮件
- 写日志
- 执行耗时任务
- 后台处理文件

一句话记忆：
> `@EnableAsync` 是“打开异步开关”，`@Async` 方法才能真正异步跑。

### 3.1.4 `@EnableConfigurationProperties(...)`

这是 Spring Boot 的注解，用来启用配置属性绑定。

比如这里：

```java
@EnableConfigurationProperties({
    CodeExecutorProperties.class,
    DataAgentProperties.class,
    FileStorageProperties.class
})
```

意思是让 Spring Boot 把 `application.yml` / `application.properties` 里的配置，自动绑定到这些 `Properties` 类上。

可以先理解成：
- `application.yml` 里写的是配置文本
- `xxxProperties` 类负责接收这些配置
- `@EnableConfigurationProperties` 负责把两者连起来

一句话记忆：
> `@EnableConfigurationProperties` 就是“让配置文件里的值自动装进 Java 配置类里”。

## 3.2 配置绑定

### 3.2.1 YAML 配置名和 Java 字段名怎么对应

配置文件和 Java 变量之间不是靠手写映射表对应的，而是靠 Spring Boot 的配置绑定规则。

比如 Java 类上写了：

```java
@ConfigurationProperties(prefix = "spring.ai.alibaba.data-agent")
public class DataAgentProperties {
    private int maxSqlRetryCount = 10;
}
```

配置文件里写：

```yaml
spring:
  ai:
    alibaba:
      data-agent:
        max-sql-retry-count: 10
```

对应过程可以理解成：

```text
spring.ai.alibaba.data-agent.max-sql-retry-count
        ↓
先用 prefix 找到 DataAgentProperties
        ↓
去掉前缀，剩下 max-sql-retry-count
        ↓
Spring Boot 自动把短横线命名转成驼峰命名
        ↓
max-sql-retry-count 对应 maxSqlRetryCount
```

所以：

```text
YAML: max-sql-retry-count
Java: maxSqlRetryCount
```

字段上的默认值：

```java
private int maxSqlRetryCount = 10;
```

可以理解成兜底值：
- 如果配置文件里写了 `max-sql-retry-count`，就用配置文件的值
- 如果配置文件里没写，就用 Java 字段上的默认值 `10`

一句话记忆：
> `@ConfigurationProperties` 先靠前缀找到类，再靠“短横线转驼峰”的规则找到字段。

### 3.2.2 配置类注解组合的整体意思

```java
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties({
    CodeExecutorProperties.class,
    DataAgentProperties.class,
    FileStorageProperties.class
})
```

整体可以通俗理解为：

> 这是一个 Spring 配置类，里面可以用 `log` 打日志；它开启了异步能力；同时把代码执行、DataAgent、文件存储相关的配置类交给 Spring 管理。

## 3.3 单元测试里的 Mockito

### 3.3.1 `@Mock` 是什么

`@Mock` 是 Mockito 提供的注解，用来创建一个“假的对象”。

它不是去 Spring 容器里找真实 Bean，也不是自动 new 一个真实实现，而是给测试准备一个可控的替身对象。这个替身默认不会执行真实逻辑，通常要配合 `when(...).thenReturn(...)` 来指定返回值。

比如：

```java
@Mock
private ApplicationContext context;
```

这表示测试里会有一个假的 `ApplicationContext`，你可以自己规定：

```java
when(context.getBean(TestNodeAction.class)).thenReturn(expected);
```

这样调用 `context.getBean(...)` 时，就会返回你提前准备好的对象。

一句话记忆：
> `@Mock` 是“Mockito 造一个假的依赖对象”，不是 Spring 真正的 Bean。

### 3.3.2 `@Mock` 和 `@MockBean` 的区别

`@Mock` 是 Mockito 的能力，只在测试代码里生效。
`@MockBean` 是 Spring Boot 测试里的能力，会把 Spring 容器中的真实 Bean 替换掉。

可以简单记成：
- `@Mock`：测试方法自己用的假对象
- `@MockBean`：Spring 容器里的 Bean 替身

一句话记忆：
> `@Mock` 偏 Mockito，`@MockBean` 偏 Spring 测试容器。

# 4. Spring AI Graph

## 4.1 核心概念

### 4.1.1 `NodeAction` 是什么

`NodeAction` 不是普通业务接口，而是 Graph 里“节点执行标准接口”。

每个节点实现它，主要是为了让 `StateGraph` 能用同一种方式调度所有节点，也就是统一调用它们的 `apply(OverAllState state)` 方法。

这样做的核心不是单纯为了代码好看，而是为了：
- 让图引擎能识别这个类是一个可执行节点
- 让不同节点都遵守同一种输入输出契约
- 方便统一编排、统一测试、统一加日志和监控

一句话记忆：
> `NodeAction` 就是 Graph 里的“节点通行证”，实现了它，节点才可以被图调度执行。

### 4.1.2 `OverAllState` 是什么

`OverAllState` 可以理解成 Graph 里的共享状态容器，也就是节点之间传递数据的上下文。

它的作用主要是：
- 上游节点把结果写进去
- 下游节点从里面读取需要的数据
- 当前节点处理完以后，再把新结果写回去

所以在这套工作流里，`OverAllState` 就像一块共享内存，里面装着用户输入、意图识别结果、Schema、SQL、执行结果等整条链路的数据。

一句话记忆：
> `OverAllState` 就是图中的共享状态，节点之间靠它传数据。

### 4.1.3 `StateGraph` 是什么

`StateGraph` 可以理解成整条图工作流的“总调度器”。

它负责把节点、边、条件分支和状态合并规则串起来，决定流程怎么走、每个节点什么时候执行、执行后状态怎么合并。

一句话记忆：
> `StateGraph` 就是“把节点连成流程并负责调度执行”的那层图定义。

### 4.1.4 `getNodeBeanAsync()` 为什么要再包一层

```java
public <T extends NodeAction> AsyncNodeAction getNodeBeanAsync(Class<T> clazz) {
    return AsyncNodeAction.node_async(getNodeBean(clazz));
}
```

这个方法的作用不是“重新创建一个新 bean”，而是把 Spring 容器里拿到的普通 `NodeAction` 再包装成一个 `AsyncNodeAction`，让图引擎可以按异步节点的方式去调度它。

可以拆成两步理解：
- `getNodeBean(clazz)`：先从 Spring 容器里拿到原始业务 bean
- `AsyncNodeAction.node_async(...)`：再给这个 bean 套上一层异步执行的适配器

这里的关键点是：
- 原始 bean 还是原来的 bean
- 变化的是“交给 Graph 执行时的节点形态”
- 这样 `StateGraph.addNode(...)` 才能接收一个图引擎能识别的异步节点

一句话记忆：
> `getNodeBeanAsync()` 不是把 bean 本体改成异步，而是把它包装成图可以异步调度的节点对象。

### 4.1.5 `KeyStrategyFactory` 是什么

`KeyStrategyFactory` 用来定义 Graph 里每个 state key 的合并策略。

简单说，就是告诉框架：
- 哪些字段要保留旧值
- 哪些字段要直接用新值覆盖

在这个项目里，大多数状态都用 `REPLACE`，因为这些字段往往代表当前轮次的最新结论、最新输出或最新计数。

一句话记忆：
> `KeyStrategyFactory` 就是“告诉 Graph 每个状态字段该怎么合并”的规则表。

# 5. 笔记维护规则

## 5.1 后续补充位置

后面如果再遇到这个项目里看不懂的类、方法、流程、配置，继续补进这里。

## 5.2 整理原则

整理原则：
- 先归类，再记细节
- 只记本质，不堆术语
- 一条口诀配一个核心概念
- 新知识优先补到原分类里，分类不够再扩展
