# Spring Boot SSE 后端教程

本文只讲后端实现，但会穿插少量前端示例，方便理解接口效果。

文中的主线分成 4 步：

1. 先理解 SSE 在协议层到底是什么
2. 再理解 Spring WebFlux 里为什么常见 `Flux<ServerSentEvent<T>>`
3. 再理解消息是如何被生产出来的
4. 最后对照 DataAgent 的真实代码

本文重点对应 DataAgent 当前采用的方案：

- Spring Boot + WebFlux
- `Flux<ServerSentEvent<T>>`
- `Sinks.Many<T>` 手动推流

## 1. 先理解 SSE 到底是什么

SSE 全称 `Server-Sent Events`，可以把它理解成：

- 浏览器发起一个普通 HTTP 请求
- 服务端不马上结束响应
- 而是在这条连接上持续往前端写一条条消息

它本质上是：

- 基于普通 HTTP 的长连接推送方式
- 服务端到浏览器的单向推送协议

它适合这种场景：

- 大模型逐 token 输出
- 工作流逐步输出中间结果
- 长任务实时进度
- 状态变更通知

它不适合这种场景：

- 前后端双向实时通信
- 浏览器需要主动频繁发消息
- 需要二进制协议

这些场景更偏向 WebSocket。

### 1.1 SSE 和 WebSocket 的区别

- WebSocket 是双向通信
- SSE 是服务端单向推送

SSE 的特点是：

- 更轻量
- 浏览器原生支持 `EventSource`
- 断线后浏览器通常会自动重连
- 很适合消息通知、进度更新、实时日志这类场景

## 2. SSE 在网络上传输时长什么样

SSE 本质上仍然是 HTTP 响应，只是响应头和响应体格式有要求。

响应头通常是：

```http
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

响应体是一条条事件，格式大致如下：

```text
event: message
id: 1
data: hello

event: message
id: 2
data: world

event: complete
data: done

```

这里有几个关键点：

- 一条事件由若干行组成
- 常见字段有 `event`、`id`、`data`
- 事件和事件之间用空行分隔
- 浏览器会持续读取，直到连接断开

前端最常见的消费方式是：

```html
<script>
  const source = new EventSource("/api/sse/demo");

  source.addEventListener("message", (event) => {
    console.log("message:", event.data);
  });

  source.addEventListener("complete", (event) => {
    console.log("complete:", event.data);
    source.close();
  });

  source.onerror = (error) => {
    console.error("sse error:", error);
  };
</script>
```

看到这里，已经可以先建立一个最基础的认知：

- SSE 不是特殊的 TCP 协议
- 它只是“按规则持续输出的 HTTP 响应”

## 3. 为什么 WebFlux 很适合做 SSE

SSE 是一种长连接推送方式。  
既然连接会持续很久，那么框架是否擅长处理“长时间不结束的请求”，就非常关键。

### 3.1 传统 Spring MVC 的特点

Spring MVC 的主流运行模型是：

- 一个请求进来
- 分配一个 Servlet 容器线程
- 线程在请求处理期间会被占住

这类模型通常被叫做：

- 同步
- 阻塞

普通接口里这没有问题，因为请求通常很快结束。  
但如果是 SSE：

- 一个客户端会建立一条长连接
- 这条连接可能持续几十秒、几分钟，甚至更久

如果同时有很多 SSE 客户端，就会出现明显压力：

- 连接数多
- 占用线程多
- 并发能力下降

Spring MVC 也能做 SSE，例如使用 `SseEmitter`，但在高并发长连接场景下并不占优。

### 3.2 Spring WebFlux 的特点

Spring WebFlux 的主流运行模型是：

- 基于响应式编程
- 基于非阻塞 I/O
- 常见运行时是 Netty 事件循环

非阻塞 IO 的价值，就是别把这些宝贵的框架线程卡死。

它更适合这种场景：

- 长连接很多
- 数据持续流式输出
- 一个请求会分多次产生结果

这类模型通常被叫做：

- 异步
- 非阻塞

### 3.3 为什么说 WebFlux 和 SSE 很配

因为 SSE 天然就是：

- 长连接
- 单向持续推送
- 数据一条条输出

而 WebFlux 天然擅长：

- 非阻塞处理连接
- 用响应式流持续输出数据
- 和 `text/event-stream` 这种媒体类型配合

所以在 Spring 体系里：

- MVC 也能做 SSE
- WebFlux 通常更适合做 SSE

特别是在这些场景里：

- 实时日志
- 审核进度推送
- 风险检测状态流
- 榜单或监控数据实时更新
- 大模型生成过程展示

## 4. 为什么普通 JSON 接口不适合这个场景

普通 JSON 接口通常是这样：

1. 浏览器发请求
2. 服务端把结果一次性算完
3. 返回一个完整 JSON
4. 连接结束

这对“最终结果很快就能得到”的场景没问题，但对下面这些场景就不合适：

- 生成 SQL 需要几秒
- Python 分析需要几十秒
- 模型中间会逐段吐出内容
- 前端希望边生成边显示

这时就需要“结果还没完全结束，但已经能先发一部分消息”的返回方式，SSE 正是解决这个问题的。

## 5. Spring Boot 里常见的两种 SSE 返回方案

Spring Boot 中，常见的 SSE 方案有两类。

### 5.1 `SseEmitter`

这是 Spring MVC 风格的方案。

它的思路是：

- Controller 返回一个 `SseEmitter`
- 后端代码再调用 `emitter.send(...)` 一条条把消息写出去

示例：

```java
@RestController
@RequestMapping("/api/sse")
public class MvcSseController {

    @GetMapping("/mvc-stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("message").data("hello"));
                emitter.send(SseEmitter.event().name("message").data("world"));
                emitter.complete();
            }
            catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

}
```

它适合：

- 项目本身是 Spring MVC
- 只是想快速加一个 SSE 接口

### 5.2 `Flux<ServerSentEvent<T>>`

这是 Spring WebFlux 风格的方案。

它的思路是：

- Controller 直接返回一个响应式流 `Flux`
- `Flux` 里的每个元素都是一条 SSE 事件
- Spring 按 `text/event-stream` 协议持续写给前端

示例：

```java
@RestController
@RequestMapping("/api/sse")
public class WebFluxSseController {

    @GetMapping(value = "/webflux-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream() {
        return Flux.interval(Duration.ofSeconds(1))
            .take(3)
            .map(index -> ServerSentEvent.<String>builder()
                .event("message")
                .id(String.valueOf(index))
                .data("hello-" + index)
                .build());
    }

}
```

DataAgent 使用的是第二种。

## 6. 必须先讲清楚的两个核心类型

DataAgent 的接口长这样：

```java
public Flux<ServerSentEvent<GraphNodeResponse>> streamSearch(...)
```

如果不把这里的两个核心类型讲清楚，后面的代码会看起来像一串泛型拼装。

这两个类型分别是：

- `Flux<T>`
- `ServerSentEvent<T>`

## 7. `Flux<T>` 到底是什么

`Flux<T>` 是 Reactor 里的响应式流类型。它不是专门给 SSE 准备的类型，而是 WebFlux / Reactor 体系里非常基础的一种“响应式流”抽象。

可以把它理解成：

- 它表示一串按顺序处理的数据
- 这串数据可以有 0 个、1 个、多个，甚至理论上无限多个
- 这些数据会以流的方式被处理，而不是先拼成一个完整集合再统一处理

如果和 Spring MVC 的思路对比，可以先这样建立直觉：

- MVC 更常见的是“方法算完一个结果，再整体返回”
- WebFlux 更常见的是“方法返回一个流，结果可以逐步产生”

也就是说，先有 `Flux`，后面才可能把 `Flux` 用到 SSE、数据库查询、消息处理、文件流式读取等场景里。

很多人在第一次接触 `Flux<T>` 时，容易把它直接理解成“异步”。这个理解不够准确。

更准确的说法是：

- `Flux<T>` 表示一串按流方式处理的数据
- 这串数据可以同步产生，也可以异步产生
- `Flux` 的重点是“流式处理”和“响应式组合”，不只是“异步”

可以先把它理解成：

- 数据不是在方法返回的这一刻就必须全部准备好
- 后续某个时间点还可以继续产生新元素
- 消费方拿到的不是一个一次性结果，而是一条会持续到来的数据流

### 7.1 `Flux<T>` 不是只能和 SSE 一起使用

`Flux<T>`` 常见的使用场景远不止 SSE。

例如：

- 流式读取数据库结果
- 接收大模型逐段输出
- 处理消息队列中的一批消息
- 定时产生任务或心跳
- 在服务层做一连串响应式处理

示例 1：普通字符串流

```java
Flux<String> flux = Flux.just("a", "b", "c");
```

示例 2：定时流

```java
Flux<Long> flux = Flux.interval(Duration.ofSeconds(1));
```

示例 3：服务层处理流

```java
Flux<Order> orders = orderService.streamOrders();
Flux<OrderDTO> result = orders
    .filter(Order::isValid)
    .map(OrderDTO::from);
```

上面这些都和 SSE 没关系，但它们都在使用 `Flux`。

### 7.2 `Flux<T>` 和普通集合的区别

如果有一个 `List<String>`：

```java
List<String> list = List.of("a", "b", "c");
```

这表示：

- 数据已经全部在内存里
- 你拿到的是一个完整结果

如果有一个 `Flux<String>`：

```java
Flux<String> flux = Flux.just("a", "b", "c");
```

这表示：

- 后端处理的是一个流式序列，不是一个已经完全展开好的集合
- 后端可以按元素做过滤、映射、日志、取消等处理
- 但这并不自动等于“HTTP 前端一定一条一条收到”

这里要特别区分两层：

1. 后端代码里的 `Flux<String>`
2. HTTP 返回给前端时最终采用什么响应格式

如果只是普通 JSON 响应，`Flux<String>` 很多时候在前端看起来仍然像一次性返回结果。  
如果配合 SSE、NDJSON 这类流式协议，前端才更容易表现为逐条接收。

### 7.3 `Flux<T>` 和 `Mono<T>` 的区别

- `Mono<T>` 表示 0 或 1 个元素
- `Flux<T>` 表示 0 到 N 个元素

SSE 之所以常用 `Flux<T>`，是因为 SSE 通常不是只返回一个值，而是持续返回很多条消息。

### 7.3.1 `Flux<T>`、线程、同步、异步之间的关系

这里最容易混淆的是三件事：

- `Flux<T>` 是不是一定会新开线程
- `Flux<T>` 是不是一定异步
- `Flux<T>` 和非阻塞是不是一回事

答案都是否定的。

`Flux<T>` 本身只是“响应式流类型”，它不等于：

- 自动新开线程
- 自动异步执行
- 自动变成非阻塞

是否使用新线程、是否异步发射，取决于：

- 数据源本身的行为
- 是否使用了调度器，比如 `subscribeOn(...)`、`publishOn(...)`
- 当前运行环境是不是 WebFlux/Netty 这样的非阻塞链路

先看一个最简单的例子：

```java
Flux<String> flux = Flux.just("a", "b", "c");
```

这段代码表示：

- 数据在创建 `Flux` 时就已经明确了
- 默认不会因为写了 `Flux.just(...)` 就自动新开线程
- 在没有切换调度器时，它经常就是在当前调用链里完成发射

所以这里更准确的说法是：

- `Flux.just(...)` 可以是同步发射
- 不能把 `Flux` 直接理解成“开线程异步返回数据”

再看一个异步例子：

```java
Flux<Long> flux = Flux.interval(Duration.ofSeconds(1)).take(3);
```

这段代码的含义是：

- 第 1 秒后收到第 1 个元素
- 第 2 秒后收到第 2 个元素
- 第 3 秒后收到第 3 个元素

这才是一个很典型的异步发射场景。

如果希望显式切换线程，也可以这样写：

```java
Flux<String> flux = Flux.just("a", "b", "c")
    .subscribeOn(Schedulers.boundedElastic());
```

这时才更接近：

- 把这条流放到别的线程池里执行

也就是说：

- `Flux` 不等于自动开线程
- 线程调度需要额外配置或由上游数据源决定

再对比一下普通集合：

```java
List<Long> list = List.of(0L, 1L, 2L);
```

这里的 3 个元素在方法返回时就已经全部准备好了。

所以可以这样区分：

- `List<T>` 更像“一个已经准备好的结果集”
- `Flux<T>` 更像“一个按流方式处理的结果序列”

再进一步说：

- `Flux.just(...)` 更偏同步发射
- `Flux.interval(...)` 更偏异步发射
- `Flux<T>` 本身既可以承载同步流，也可以承载异步流
- 是否切线程，不由 `Flux` 这个类型本身单独决定

### 7.3.2 `Flux<T>` 放到 WebFlux 里时，为什么常被说成异步非阻塞

`Flux<T>` 自己不是“异步框架”，但当它工作在 Spring WebFlux 里时，通常会配合：

- 非阻塞 I/O
- Reactor 调度模型
- Netty 事件循环

所以在 WebFlux 项目里，你经常会看到：

- `Flux<T>` 表达一串数据
- 这串数据按异步、非阻塞的方式被处理和发送

因此更准确的表述应该是：

- `Flux<T>` 是响应式流类型
- Spring WebFlux 是异步非阻塞 Web 框架
- 两者结合后，很适合表达异步非阻塞的数据流处理
- 但不能把 `Flux<T>` 单独理解成“自动开线程的异步对象”

### 7.4 `Flux<T>` 在 SSE 里扮演什么角色

当 `Flux<T>` 被放到 SSE 接口里时，它才开始承担额外角色。

在 SSE 接口中，`Flux<T>` 负责表达：

- 这不是一次性结果
- 这是一条持续输出的消息流

例如：

```java
Flux<String> flux = Flux.just("start", "running", "done");
```

这不是说“方法返回一个字符串数组”，而是说：

- 后端会按流式顺序处理这 3 个元素
- 如果再配合 SSE 这样的流式协议，前端才会更容易表现为先收到 `start`，再收到 `running`，最后收到 `done`

所以要分开理解：

- 在一般业务代码里，`Flux<T>` 是响应式流类型
- 在 SSE 接口里，`Flux<T>` 变成了“持续写给浏览器的响应流”

### 7.4.1 `List<User>`、`Flux<User>`、`Flux<ServerSentEvent<User>>` 的区别

假设系统里有 3 个用户：

- Alice
- Bob
- Charlie

#### `List<User>`

```java
@GetMapping("/users/list")
public List<User> users() {
    return List.of(
        new User("Alice"),
        new User("Bob"),
        new User("Charlie")
    );
}
```

这表示：

- 后端一次性把 3 个用户都准备好

响应效果类似：

```json
[
  { "name": "Alice" },
  { "name": "Bob" },
  { "name": "Charlie" }
]
```

#### `Flux<User>`

```java
@GetMapping("/users/flux")
public Flux<User> users() {
    return Flux.just(
        new User("Alice"),
        new User("Bob"),
        new User("Charlie")
    );
}
```

这里后端已经在使用 `Flux<User>` 这种流式语义：

- 后端内部处理的是 `Flux<User>`
- 数据是按流的方式组织的

但如果这个接口仍然按普通 JSON 返回，前端很多时候看到的效果依然像：

```json
[
  { "name": "Alice" },
  { "name": "Bob" },
  { "name": "Charlie" }
]
```

也就是说：

- 后端是 `Flux<User>`
- 前端不一定逐条接收
- 前端可能还是等完整响应结束后再拿到结果

所以 `Flux<User>` 不等于“前端一定流式显示”，也不等于“前端一定逐条收到”。

#### `Flux<ServerSentEvent<User>>`

```java
@GetMapping(value = "/users/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<User>> users() {
    return Flux.just(
        new User("Alice"),
        new User("Bob"),
        new User("Charlie")
    )
        .delayElements(Duration.ofSeconds(1))
        .map(user -> ServerSentEvent.builder(user).event("user").build());
}
```

这里就真正进入 SSE 场景了：

- 后端每隔一段时间发一条 SSE 事件
- 前端可以一条条接收

大致效果像这样：

第 1 秒：

```text
event: user
data: {"name":"Alice"}

```

第 2 秒：

```text
event: user
data: {"name":"Bob"}

```

第 3 秒：

```text
event: user
data: {"name":"Charlie"}

```

前端写法：

```javascript
const source = new EventSource("/users/sse");

source.addEventListener("user", (event) => {
  console.log("收到一个用户:", event.data);
});
```

这时前端的体验是：

- 先收到 Alice
- 再收到 Bob
- 再收到 Charlie

#### 这三个例子的结论

- `List<User>` 常见表现是一整批结果一次性返回
- `Flux<User>` 表示后端是流式语义，但前端未必流式接收
- `Flux<ServerSentEvent<User>>` 才是浏览器最典型的逐条 SSE 接收方式

### 7.5 `Flux<T>` 常见 API 是干什么的

#### `map(...)`

把流里的每个元素转换成另一个元素。

```java
Flux.just("a", "b", "c")
    .map(value -> value.toUpperCase());
```

作用：

- 元素类型转换
- 构造响应对象
- 把业务对象包装成 `ServerSentEvent`

#### `filter(...)`

过滤掉不需要的元素。

```java
Flux.just("a", "", "c")
    .filter(value -> !value.isEmpty());
```

作用：

- 过滤空消息
- 过滤无效状态
- 只保留需要发给前端的事件

#### `take(...)`

只接收前 N 个元素，达到数量后流就结束。

```java
Flux.interval(Duration.ofSeconds(1)).take(3);
```

作用：

- 控制示例流结束
- 截断无限流

说明：

- `take(...)` 不是只能和 `interval(...)` 一起使用
- 它可以作用在任何 `Flux` 上
- 只是 `interval(...)` 产生的是一个默认不会自己结束的定时流，所以示例里经常和 `take(...)` 配合使用

上面的例子表示：

- `interval(...)` 每秒产生一个数字
- `take(3)` 让这条流一共只发出 3 个元素
- 第 3 个元素发完之后，流会正常结束

#### `doOnSubscribe(...)`

订阅建立时触发。

```java
flux.doOnSubscribe(s -> log.info("subscribed"));
```

作用：

- 打日志
- 记录开始时间
- 初始化上下文

#### `doOnCancel(...)`

订阅被取消时触发。

```java
flux.doOnCancel(() -> log.info("cancelled"));
```

作用：

- 浏览器断开时清理资源
- 停止后台任务

#### `doOnError(...)`

流发生异常时触发。

#### `doOnComplete(...)`

流正常完成时触发。

#### `doFinally(...)`

无论完成、取消还是异常，最后都会走到这里。

这些生命周期 API 在 SSE 场景里很重要，因为 SSE 最大的风险之一不是“发不出去”，而是“连接断了之后后台还在继续跑”。

### 7.6 什么时候只用 `Flux<T>`，什么时候和 SSE 结合

可以这样区分：

#### 只用 `Flux<T>` 的场景

这时 `Flux<T>` 只是服务端内部的数据流抽象。

例如：

- Service 返回 `Flux<Order>`
- 大模型 SDK 返回 `Flux<ChatResponse>`
- Repository 返回 `Flux<Record>`

这类场景下，`Flux<T>` 只是在后端代码里流动，不一定直接暴露给浏览器。

#### `Flux<T>` 和 SSE 结合的场景

这时的目标是把一串数据持续发给前端。

常见写法：

```java
public Flux<ServerSentEvent<String>> stream() {
    return Flux.just("start", "running", "done")
        .map(item -> ServerSentEvent.builder(item).build());
}
```

这里做了两件事：

1. 先用 `Flux<String>` 表达“有一串流式消息”
2. 再用 `ServerSentEvent<String>` 把每条消息包装成 SSE 事件

也就是说：

- `Flux` 负责“流式序列”
- `ServerSentEvent` 负责“SSE 事件格式”

## 8. `ServerSentEvent<T>` 到底是什么

如果说 `Flux<T>` 解决的是“这是一串持续输出的数据”，那么 `ServerSentEvent<T>` 解决的是：

- 这串数据里的每一个元素，到底要按什么 SSE 格式发给前端

可以把 `ServerSentEvent<T>` 理解成：

- 一条 SSE 消息的 Java 包装对象

最常见的构造方式：

```java
ServerSentEvent.<String>builder()
    .event("message")
    .id("1")
    .data("hello")
    .build();
```

上面这段代码对应的 SSE 语义大致是：

```text
event: message
id: 1
data: hello

```

### 8.1 为什么不直接返回 `Flux<String>`

Spring WebFlux 确实可以直接返回 `Flux<String>`，但这样做有一个限制：

- 你只能发内容
- 不方便显式控制 `event`、`id`、`comment`、`retry`

而 `ServerSentEvent<T>` 可以把一条消息的这些元信息完整描述出来，所以在真实项目里更常见。

### 8.2 `ServerSentEvent<T>` 常用 API

#### `builder(...)`

创建一个 SSE 事件构造器。

```java
ServerSentEvent.builder("hello");
```

这里的 `"hello"` 会作为 `data`。

#### `data(...)`

设置业务数据。

```java
ServerSentEvent.<String>builder()
    .data("hello")
    .build();
```

作用：

- 放真正要传给前端的内容
- 如果是对象，Spring 会自动序列化成 JSON

#### `event(...)`

设置事件名。

```java
ServerSentEvent.<String>builder()
    .event("complete")
    .data("done")
    .build();
```

前端可以按事件名监听：

```javascript
source.addEventListener("complete", event => {
  console.log(event.data);
});
```

#### `id(...)`

设置事件 ID。

```java
ServerSentEvent.<String>builder()
    .id("msg-1001")
    .data("hello")
    .build();
```

作用：

- 去重
- 断线续传
- 问题排查

#### `comment(...)`

发送注释型事件。

```java
ServerSentEvent.<String>builder()
    .comment("heartbeat")
    .build();
```

它最常见的用途不是业务消息，而是心跳保活。

要先区分两个概念：

- 前端业务代码是否能监听到它
- 这条 SSE 连接在链路层面是否一直有数据流过

`comment(...)`` 的特点是：

- 浏览器前端通常不会把它当作业务事件处理
- 但它确实会向连接里写入数据

这就意味着：

- 前端一般不会在 `onmessage` 或 `addEventListener(...)` 里拿到 comment
- 但 Nginx、网关、负载均衡、浏览器连接层会看到“这条连接不是完全空闲的”

这也是为什么 SSE 经常用 comment 做 heartbeat。

举个例子：

- 用户发起一个 SSE 请求
- 后端真实业务 40 秒后才有第一条结果
- 如果这 40 秒里一条数据都没有
- 中间层可能会因为连接空闲太久而断开

如果服务端每 10 秒发一次：

```text
: heartbeat

```

虽然前端业务代码一般不会收到这条 heartbeat，但链路会认为：

- 服务端还活着
- 这不是死连接
- 这条 SSE 连接不该因为长时间空闲被提前关闭

所以 `comment(...)` 的核心作用不是“通知前端业务层”，而是：

- 保持连接活跃
- 降低空闲超时导致的断连风险

常用场景：

- 心跳保活
- 防止代理过早断开

#### `retry(...)`

设置客户端建议重连间隔。

```java
ServerSentEvent.<String>builder()
    .retry(Duration.ofSeconds(3))
    .data("hello")
    .build();
```

### 8.3 `ServerSentEvent<T>` 的读取 API

除了构造事件，后端有时也需要读取事件对象。

#### `sse.event()`

读取事件名。

#### `sse.data()`

读取业务数据。

DataAgent 在过滤 SSE 时就用到了这两个读取 API。

## 9. 为什么 DataAgent 返回的是 `Flux<ServerSentEvent<GraphNodeResponse>>`

现在把这三个层次连起来看：

- `GraphNodeResponse`
  表示一条业务消息长什么样
- `ServerSentEvent<GraphNodeResponse>`
  表示这条业务消息要按 SSE 事件发送
- `Flux<ServerSentEvent<GraphNodeResponse>>`
  表示这些 SSE 事件会持续不断地发给前端

这正好对应真实需求：

- Graph 在运行过程中会不断产出消息
- 每条消息都要带业务字段
- 前端需要边收边显示

## 10. 最小可运行示例

下面先看一个很小但完整的 WebFlux SSE 接口。

```java
@RestController
@RequestMapping("/api/sse")
public class DemoSseController {

    @GetMapping(value = "/hello", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> hello() {
        return Flux.interval(Duration.ofSeconds(1))
            .take(3)
            .map(index -> ServerSentEvent.<String>builder()
                .event("message")
                .id(String.valueOf(index))
                .data("hello-" + index)
                .build());
    }

}
```

这段代码可以按下面顺序理解：

1. `produces = MediaType.TEXT_EVENT_STREAM_VALUE`
   告诉 Spring 这是 SSE 响应
2. `Flux.interval(...)`
   每秒产生一个数字
3. `take(3)`
   一共发 3 次
4. `map(...)`
   把数字包装成 `ServerSentEvent<String>`

## 11. `MediaType.TEXT_EVENT_STREAM_VALUE` 的作用

SSE 接口最容易漏的就是这一行：

```java
@GetMapping(value = "/hello", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

它的作用是：

- 告诉 Spring 这个接口不是普通 JSON
- 响应要按 `text/event-stream` 格式输出
- 浏览器也会按 SSE 方式处理它

没有这个声明，很多时候前后端都不会按 SSE 的语义工作。

## 12. 消息是怎么生产出来的

讲清返回类型之后，还要讲另外一个核心问题：

- 这条 `Flux` 里的消息，后端是怎么生产出来的

这是学习 SSE 时最容易混乱的地方。

Controller 返回 `Flux<ServerSentEvent<T>>`，只是在声明“接口输出是一条流”。  
真正决定这条流怎么产生的，是消息生产方式。

## 13. 常见消息生产方式总览

| 方式 | 数据来源 | 生产特点 | 适合场景 | 是否常用于 SSE |
| --- | --- | --- | --- | --- |
| 现成 `Flux<T>` | 已有响应式数据源 | 直接转换 | SDK、数据库驱动、已有流式处理链 | 是 |
| `Flux.just(...)` | 固定值 | 少量固定元素 | 固定阶段消息 | 是 |
| `Flux.fromIterable(...)` | 集合 | 顺序输出集合元素 | 列表、数组、批量结果 | 是 |
| `Flux.interval(...)` | 定时器 | 周期性产出 | 心跳、定时通知 | 是 |
| `Flux.generate(...)` | 同步状态机 | 一次生成一个元素 | 同步逐步生成 | 是 |
| `Flux.create(...)` | callback/listener | 支持外部回调推送 | 事件适配、SDK 回调桥接 | 是 |
| `Flux.push(...)` | 单线程 callback | 比 `create` 更收敛 | 单生产者事件源 | 是 |
| `Sinks.One<T>` | 手动单次发射 | 最终只发一个结果 | 单值结果桥接 | 偶尔 |
| `Sinks.Many<T>` | 手动多次发射 | 多次主动推送 | 长任务、多阶段流式过程 | 很常见 |

## 14. 什么时候不用 `Sinks.Many<T>`

`Sinks.Many<T>` 很常见，但不是只要做 SSE 就必须上它。

### 14.1 已经有现成 `Flux<T>`

如果你的 SDK 或业务层已经直接返回 `Flux<T>`，最简单的做法通常是直接转换：

```java
return sourceFlux.map(item -> ServerSentEvent.builder(item).build());
```

这类场景包括：

- 大模型 SDK 已经给你 `Flux<ChatResponse>`
- 数据库驱动本身就是响应式
- 上游已经是一条完整的 Reactor 链

### 14.2 只是固定少量消息

```java
return Flux.just("start", "running", "done")
    .map(item -> ServerSentEvent.builder(item).build());
```

### 14.3 数据就在集合里

```java
return Flux.fromIterable(messages)
    .map(item -> ServerSentEvent.builder(item).build());
```

### 14.4 只是定时通知

```java
return Flux.interval(Duration.ofSeconds(2))
    .map(i -> ServerSentEvent.builder("heartbeat").comment("heartbeat").build());
```

### 14.5 同步逐步生成

```java
return Flux.generate(
    () -> 0,
    (state, sink) -> {
        sink.next("step-" + state);
        if (state == 4) {
            sink.complete();
        }
        return state + 1;
    }
).map(item -> ServerSentEvent.builder(item).build());
```

这些场景都不一定需要 `Sinks.Many<T>`。

## 15. `Sinks.One<T>` 和 `Sinks.Many<T>` 分别解决什么问题

`Sink` 可以理解成：

- 主动往响应式流里发消息的入口

它和 `Flux.just(...)` 这类声明式写法不同，`Sink` 更适合：

- 后台任务在别的线程运行
- 消息会在未来某个时刻主动到来

### 15.1 `Sinks.One<T>`

`Sinks.One<T>` 用于：

- 最终只会成功一次或失败一次
- 只需要发射一个值

示例：

```java
Sinks.One<String> sink = Sinks.one();

CompletableFuture.runAsync(() -> {
    sink.tryEmitValue("done");
});

return sink.asMono();
```

它更像“单值结果桥接”。

不适合：

- 持续发很多条 SSE 消息
- 多阶段进度输出

### 15.2 `Sinks.Many<T>`

`Sinks.Many<T>` 用于：

- 需要多次发消息
- 可能在多个时刻发消息
- 需要在后台任务里手动控制 `next / complete / error`

示例：

```java
Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

sink.tryEmitNext("start");
sink.tryEmitNext("running");
sink.tryEmitNext("done");
sink.tryEmitComplete();
```

它更适合长任务、流式工作流、多阶段进度场景。

## 16. 什么时候适合用 `Sinks.Many<T>`

下面这些场景非常适合。

### 16.1 后台任务和 HTTP 返回线程分离

比如：

- Controller 先返回一条流
- 线程池里的任务再持续产出消息

### 16.2 一次请求里会从多个位置发消息

比如：

- 任务开始时发一条
- 中途发进度
- 结束时发完成事件

### 16.3 需要桥接 callback / listener / 外部回调

比如：

- 旧 SDK 是 listener 风格
- 工作流框架中间结果通过回调返回

### 16.4 需要显式控制流的结束

比如：

- 先发 `message`
- 再发 `complete`
- 最后主动结束流

## 17. `Sinks.Many<T>` 常用 API

### 17.1 `Sinks.many().unicast().onBackpressureBuffer()`

单播。

含义是：

- 只允许一个订阅者
- 当消费方稍慢时先做缓冲

这很适合“一个请求对应一个 SSE 连接”的场景。

### 17.2 `Sinks.many().multicast().onBackpressureBuffer()`

多播。

含义是：

- 多个订阅者共享同一事件源

适合广播型场景。

### 17.3 `tryEmitNext(...)`

推送一条消息。

```java
Sinks.EmitResult result = sink.tryEmitNext(data);
```

### 17.4 `Sinks.EmitResult`

表示发射结果。

之所以要返回结果，是因为发射并不一定总能成功，例如：

- 连接已经断开
- sink 已经结束
- 当前状态不允许继续发射

所以常见写法是：

```java
Sinks.EmitResult result = sink.tryEmitNext(data);
if (result.isFailure()) {
    // 记录日志或清理任务
}
```

### 17.5 `tryEmitComplete()`

主动结束流。

### 17.6 `tryEmitError(...)`

以错误信号结束流。

### 17.7 `asFlux()`

把 `sink` 暴露成 `Flux`。

这一步很关键，因为：

- `sink` 是生产入口
- `Flux` 是 Controller 最终返回给前端的流

## 18. DataAgent 为什么使用 `Sinks.Many<T>`

DataAgent 的流不是简单的：

- 定时器流
- 集合流
- 单个结果

它的真实情况是：

1. Controller 先建立 SSE 通道
2. GraphService 异步启动 Graph
3. Graph 在后台持续产出 `NodeOutput`
4. 服务层把这些输出转成 `ServerSentEvent<GraphNodeResponse>`
5. 正常结束或异常结束时再显式收尾

这类场景正适合 `Sinks.Many<T>`。

## 19. DataAgent 的入口签名怎么理解

DataAgent 的核心 SSE 接口在：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

方法签名：

```java
public Flux<ServerSentEvent<GraphNodeResponse>> streamSearch(...)
```

可以分三层看：

- `GraphNodeResponse`
  一条业务消息
- `ServerSentEvent<GraphNodeResponse>`
  一条 SSE 事件
- `Flux<ServerSentEvent<GraphNodeResponse>>`
  一串持续发给前端的 SSE 事件

## 20. `GraphController` 做了什么

`GraphController.streamSearch(...)` 主要做 6 件事。

### 20.1 声明这是 SSE 接口

```java
@GetMapping(value = "/stream/search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

### 20.2 设置响应头

```java
response.getHeaders().add("Cache-Control", "no-cache");
response.getHeaders().add("Connection", "keep-alive");
response.getHeaders().add("Access-Control-Allow-Origin", "*");
```

作用：

- 不缓存
- 保持连接
- 允许跨域

### 20.3 创建 `Sinks.Many<ServerSentEvent<GraphNodeResponse>>`

```java
Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink =
    Sinks.many().unicast().onBackpressureBuffer();
```

### 20.4 组装请求对象

把 HTTP 参数组装成 `GraphRequest`。

### 20.5 把执行权交给 `GraphService`

```java
graphService.graphStreamProcess(sink, request);
```

这一步之后，真正的后台执行和推流都在服务层发生。

### 20.6 返回 `sink.asFlux()` 并绑定生命周期

```java
return sink.asFlux()
    .filter(...)
    .doOnSubscribe(...)
    .doOnCancel(...)
    .doOnError(...)
    .doOnComplete(...);
```

## 21. `GraphController` 里用到的 `Flux` API 在干什么

### 21.1 `filter(...)`

过滤掉不该发给前端的消息。

DataAgent 的逻辑是：

- `complete` 和 `error` 事件必须放行
- 普通空文本消息不要发给前端

### 21.2 `doOnSubscribe(...)`

记录订阅建立。

### 21.3 `doOnCancel(...)`

浏览器断开时，停止后台任务。

这一步非常关键。  
如果没有它，前端虽然断开了，但 Graph 可能还在后台继续跑。

### 21.4 `doOnError(...)`

流异常时做兜底清理。

### 21.5 `doOnComplete(...)`

流结束时打完成日志。

## 22. 服务层是怎么把 Graph 输出变成 SSE 的

关键代码在：

- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

它的大致过程是：

1. Graph 产生 `Flux<NodeOutput>`
2. 服务层异步订阅这条 `Flux`
3. 每来一个 `StreamingOutput`
4. 就组装一个 `GraphNodeResponse`
5. 再包装成 `ServerSentEvent<GraphNodeResponse>`
6. 最后调用 `sink.tryEmitNext(...)`

也就是说，SSE 并不是 Graph 天然自带的，而是服务层把 Graph 输出桥接成 SSE。

## 23. `ServerSentEvent<T>` 在 DataAgent 里怎么用

DataAgent 主要用了这些能力：

### 23.1 普通数据事件

```java
ServerSentEvent.builder(response).build()
```

表示：

- 把 `GraphNodeResponse` 当作数据体发给前端

### 23.2 完成事件

```java
ServerSentEvent.builder(GraphNodeResponse.complete(agentId, threadId))
    .event(STREAM_EVENT_COMPLETE)
    .build();
```

表示：

- 这不仅是一条数据
- 还是一个名为 `complete` 的结束事件

### 23.3 错误事件

```java
ServerSentEvent.builder(GraphNodeResponse.error(...))
    .event(STREAM_EVENT_ERROR)
    .build();
```

表示：

- 前端可以把它当作命名错误事件处理



## 24. 读这篇教程时的主线

如果把整篇内容压缩成一条理解路径，应该按下面顺序去看：

1. SSE 是持续输出的 HTTP 响应
2. `Flux<T>` 表示一串按流方式处理的数据
3. `ServerSentEvent<T>` 表示其中一条 SSE 事件
4. `Flux<ServerSentEvent<T>>` 表示持续发送的 SSE 事件流
5. 消息既可以直接来自 `Flux`，也可以来自 `Sinks`
6. DataAgent 选择的是 `Flux<ServerSentEvent<T>> + Sinks.Many<T>` 这套方案

按这个顺序再去看 `GraphController` 和 `GraphServiceImpl`，会比一开始直接盯着泛型类型更容易看懂。
