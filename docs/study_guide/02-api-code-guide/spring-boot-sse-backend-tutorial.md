# Spring Boot SSE 后端实战教程


本文以 DataAgent 当前实际采用的方案为主：

- Spring Boot + WebFlux
- `Flux<ServerSentEvent<T>>`
- `Sinks.Many<T>` 手动推送事件


## 1. SSE 是什么

SSE 全称 Server-Sent Events，意思是服务端通过一个长连接持续往浏览器推消息。

它很适合这些场景：

- 大模型逐 token 输出
- 工作流实时进度
- 报表生成过程流式展示
- 会话列表、任务状态、监控事件推送

SSE 的特点：

- 基于 HTTP，服务端单向推送
- 浏览器原生支持 `EventSource`
- 数据格式简单，默认 `Content-Type` 是 `text/event-stream`
- 很适合“后端一直产出，前端持续显示”的场景

它不适合这些场景：

- 前后端双向实时通信
- 需要浏览器主动频繁发消息
- 需要二进制长连接协议

这类场景更偏向 WebSocket。

## 2. Spring Boot 里常见的两种 SSE 写法

Spring Boot 里做 SSE，常见有两条路线：

### 2.1 `SseEmitter`

这是 Spring MVC 风格，更适合传统阻塞式项目。

### 2.2 `Flux<ServerSentEvent<T>>`

这是 Spring WebFlux 风格，更适合响应式、流式输出、Reactor 链路。

DataAgent 用的是第二种，所以本文重点讲它。

## 2.3 先理解两个层次

学习 SSE 时，最好把下面两个问题分开看：

### 2.3.1 接口返回什么

这一层对应 Controller 方法签名。

常见选择有：

- `SseEmitter`
- `Flux<ServerSentEvent<T>>`

### 2.3.2 消息从哪里来

这一层对应后端如何生产流里的每一条消息。

常见方式有：

- `Flux.just(...)`
- `Flux.fromIterable(...)`
- `Flux.interval(...)`
- `Flux.generate(...)`
- `Flux.create(...)`
- `Flux.push(...)`
- `Sinks.One<T>`
- `Sinks.Many<T>`

这两个层次分开之后，接口设计和消息生产方式就更容易理解了。

## 2.4 返回层怎么选：`SseEmitter` 还是 `Flux<ServerSentEvent<T>>`

### 2.4.1 `SseEmitter`

`SseEmitter` 是 Spring MVC 风格的 SSE 返回方案。

它适合：

- 项目本身就是 Spring MVC
- Controller 不是 Reactor 风格
- 只是想快速补一个 SSE 接口

典型写法：

```java
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
```

优点：

- 上手快
- 传统 Spring MVC 项目接入成本低

限制：

- 和 Reactor 链路不好自然衔接
- 流组合能力不如 WebFlux
- 如果项目本身已经是响应式流，写起来会别扭

### 2.4.2 `Flux<ServerSentEvent<T>>`

`Flux<ServerSentEvent<T>>` 是 Spring WebFlux 风格的 SSE 返回方案。

它适合：

- 项目已经在用 WebFlux / Reactor
- 接口本身就是持续产出的流
- 你希望自然使用 `map`、`filter`、`merge`、`doOnCancel` 这些 Reactor API

典型写法：

```java
@GetMapping(value = "/webflux-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream() {
    return Flux.interval(Duration.ofSeconds(1))
        .map(i -> ServerSentEvent.<String>builder()
            .event("message")
            .data("hello-" + i)
            .build());
}
```

优点：

- 和 Reactor、WebFlux 完全一致
- 很容易做流式加工和生命周期处理
- 特别适合大模型流式输出、工作流流式输出

DataAgent 用的就是这个返回方案：

- 接口返回 `Flux<ServerSentEvent<GraphNodeResponse>>`

### 2.4.3 使用建议

- 传统 Spring MVC 项目可以使用 `SseEmitter`
- WebFlux / Reactor 项目可以使用 `Flux<ServerSentEvent<T>>`

## 2.5 消息生产层怎么选：什么时候该用 `Sinks.Many<T>`

`Sinks.Many<T>` 解决的不是“接口返回什么”，而是：

- 你已经决定返回 `Flux<ServerSentEvent<T>>`
- 但消息并不是一个天然现成的 `Flux`
- 你需要一个地方手动把消息不断塞进这条流

### 2.5.1 什么时候不用 `Sinks.Many<T>`

如果消息天然就是一个 `Flux`，通常就不用 `Sinks.Many<T>`。

最常见的几种情况：

#### 情况 1：你本来就有现成的响应式数据源

比如模型 SDK、数据库驱动、消息流本身就返回 `Flux<T>`：

```java
return llmService.stream()
    .map(chunk -> ServerSentEvent.builder(chunk).event("message").build());
```

这种时候直接 `map` 就行，不需要先绕到 `Sinks.Many<T>`。

#### 情况 2：你只是从集合、数组、固定结果里顺序发消息

```java
return Flux.fromIterable(List.of("a", "b", "c"))
    .map(item -> ServerSentEvent.builder(item).build());
```

这种也不需要 `Sinks.Many<T>`。

#### 情况 3：你只是定时推送

```java
return Flux.interval(Duration.ofSeconds(1))
    .map(i -> ServerSentEvent.builder("tick-" + i).event("tick").build());
```

这种场景 `Flux.interval(...)` 已经够用了。

#### 情况 4：你是同步按步生成数据

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

这种更适合 `Flux.generate(...)`。

### 2.5.2 什么时候适合用 `Sinks.Many<T>`

当消息不是自然从一条现成 Flux 链里长出来，而是来自“外部异步世界”时，`Sinks.Many<T>` 很合适。

典型场景：

#### 场景 1：后台任务和 HTTP 返回线程分离

比如：

- 任务在线程池里跑
- 消息由回调产出
- 你要边跑边推

#### 场景 2：一个请求生命周期里，会从多个位置发消息

比如：

- 刚开始先推一条“任务已开始”
- 中途某个 Service 推进度
- 结束时再推 `complete`

如果这些消息不是在同一条简单的 Reactor 链上产生，`Sinks.Many<T>` 会比拼一条超长 Flux 链更清楚。

#### 场景 3：你要把 imperative 风格桥接成 Flux

比如：

- 旧 SDK 用 listener / callback
- 工作流框架通过回调给你中间结果
- 某个库在任意时刻通知事件

这时候 `Sinks.Many<T>` 很适合做桥接。

#### 场景 4：你需要显式控制 `next / complete / error`

比如：

- 想先发几条业务消息
- 再显式发 `complete` 事件
- 最后手动结束流

### 2.5.3 DataAgent 为什么适合用 `Sinks.Many<T>`

DataAgent 不是简单的：

- `Flux.interval(...)`
- `Flux.fromIterable(...)`
- 一个现成 SDK `Flux<T>` 直接返回

它的消息来源更复杂：

- Controller 先建立 SSE 通道
- `GraphServiceImpl` 异步启动 Graph
- Graph 在后台持续产出 `NodeOutput`
- 服务层要把这些中间结果不断桥接到 HTTP SSE
- 完成、错误、取消还要显式清理和收尾

这类场景里，`Sinks.Many<T>` 适合把后台任意时刻产出的消息转换成前端可订阅的 `Flux`。

## 2.6 Flux 和 Sink 的常见消息生产方式

这一节把常见方式放在一起说明，并给出适用场景。

### 2.6.1 方式总览

| 方式                       | 生产特点       | 适合场景                   | 是否适合 SSE | 备注             |
| ------------------------ | ---------- | ---------------------- | -------- | -------------- |
| `Flux.just(...)`         | 固定少量元素，声明式 | 少量固定消息                 | 是        | 最简单            |
| `Flux.fromIterable(...)` | 从集合顺序发出    | 列表、数组、批量结果             | 是        | 常用于已有集合        |
| `Flux.interval(...)`     | 定时产生元素     | 心跳、轮询、定时通知             | 是        | 常配合 `map`      |
| `Flux.generate(...)`     | 同步逐个生成     | 状态机、逐步演算               | 是        | 一次只能发一个        |
| `Flux.create(...)`       | 多线程/多次回调适配 | listener、callback、异步桥接 | 是        | 适合外部事件源        |
| `Flux.push(...)`         | 单线程回调适配    | 单生产者 callback          | 是        | 比 `create` 更收敛 |
| 现成 `Flux<T>`             | 直接转换       | SDK、数据库驱动、已有响应式数据源     | 是        | 转换成本低          |
| `Sinks.One<T>`           | 单次发射一个结果   | 只会成功一次或失败一次的异步结果       | 条件适合     | 单值异步桥接         |
| `Sinks.Many<T>`          | 多次手动发射     | 长任务、多阶段推送、多处发消息        | 是        | 适合复杂流式过程       |

### 2.6.2 直接返回现成 `Flux`

如果你已经有：

- `Flux<ChatResponse>`
- `Flux<NodeOutput>`
- `Flux<String>`

直接做转换通常比较直接：

```java
return sourceFlux.map(item -> ServerSentEvent.builder(item).build());
```

这种方式适合：

- 外部 SDK 已经返回 `Flux<T>`
- 数据库驱动本身就是响应式
- 业务层已经拿到现成的响应式流

### 2.6.3 `Flux.just(...)`

适合固定少量消息。

```java
return Flux.just("start", "running", "done")
    .map(item -> ServerSentEvent.builder(item).build());
```

适合：

- 固定文案
- 固定阶段消息
- 本次请求只返回少量已知结果

### 2.6.4 `Flux.fromIterable(...)`

适合集合数据。

```java
return Flux.fromIterable(messages)
    .map(item -> ServerSentEvent.builder(item).build());
```

适合：

- 已经拿到一个集合
- 想把集合项逐条发成 SSE

### 2.6.5 `Flux.interval(...)`

适合定时、心跳、轮询型消息。

```java
return Flux.interval(Duration.ofSeconds(2))
    .map(i -> ServerSentEvent.builder("heartbeat").comment("heartbeat").build());
```

适合：

- 心跳保活
- 定时状态推送
- 周期性监控消息

### 2.6.6 `Flux.generate(...)`

适合同步、一步一步地生成数据。

特点是：

- 一次生成一个
- 更偏同步状态机

适合：

- 每一步都依赖上一步状态
- 数据可以同步逐个计算出来

### 2.6.7 `Flux.create(...)`

适合把 callback/listener 风格的数据源适配成 Flux。

```java
return Flux.create(sink -> {
    listener.onMessage(message -> sink.next(message));
    listener.onComplete(() -> sink.complete());
    listener.onError(sink::error);
}).map(item -> ServerSentEvent.builder(item).build());
```

适合：

- callback 风格 API
- listener 风格 SDK
- 需要把外部异步事件转成 Flux

和 `Sinks.Many<T>` 的区别在于：

- `Flux.create(...)` 通常在创建 Flux 时就把生产逻辑一并定义好
- `Sinks.Many<T>` 通常先保留一个入口，再由别处在后续时刻发消息

### 2.6.8 `Flux.push(...)`

也适合 callback 适配，但更适合单线程生产者。

如果你明确知道消息只会从单线程进入，可以考虑它。

### 2.6.9 `Sinks.One<T>`

`Sinks.One<T>` 用来发射单个结果。

典型写法：

```java
Sinks.One<String> sink = Sinks.one();

CompletableFuture.runAsync(() -> {
    sink.tryEmitValue("done");
});

return sink.asMono();
```

适合：

- 某个异步任务只会完成一次
- 最终只需要一个结果
- 需要把 callback / future 风格桥接成 `Mono<T>`

不太适合：

- 长时间持续推送多条 SSE 消息
- 多阶段进度展示

如果要做 SSE，`Sinks.One<T>` 更适合“只发一条最终通知”的场景，不适合像 DataAgent 这种连续流。

### 2.6.10 `Sinks.Many<T>`

适合：

- 多处发消息
- 先持有入口，再异步发消息
- 更明确地控制单播、多播、背压和完成时机

### 2.6.11 选型建议

| 场景                     | 更合适的方式                                |
| ---------------------- | ------------------------------------- |
| 已经有现成 `Flux<T>`        | 直接返回或 `map`                           |
| 固定少量消息                 | `Flux.just(...)`                      |
| 来自集合                   | `Flux.fromIterable(...)`              |
| 定时或心跳                  | `Flux.interval(...)`                  |
| 同步状态推进                 | `Flux.generate(...)`                  |
| callback / listener 适配 | `Flux.create(...)` 或 `Flux.push(...)` |
| 只有一个异步结果               | `Sinks.One<T>`                        |
| 多阶段、长任务、手动推流           | `Sinks.Many<T>`                       |

## 3. 先看最小可运行版本

先看一个最小 SSE 接口：

```java
@RestController
@RequestMapping("/api/sse")
public class DemoSseController {

    @GetMapping(value = "/hello", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> hello() {
        return Flux.interval(Duration.ofSeconds(1))
            .take(5)
            .map(index -> ServerSentEvent.<String>builder()
                .event("message")
                .id(String.valueOf(index))
                .data("hello-" + index)
                .build());
    }

}
```

这里先记住 3 个点：

- `produces = MediaType.TEXT_EVENT_STREAM_VALUE`
- 返回值是 `Flux<ServerSentEvent<String>>`
- 每个元素都会被 Spring 当成一条 SSE 事件发给前端

前端可以这样验证：

```html
<script>
  const source = new EventSource("/api/sse/hello");

  source.addEventListener("message", (event) => {
    console.log("message event:", event.data);
  });

  source.onerror = (error) => {
    console.error("sse error", error);
  };
</script>
```

## 4. DataAgent 实际用的返回签名

DataAgent 的入口不是简单字符串，而是：

```java
public Flux<ServerSentEvent<GraphNodeResponse>> streamSearch(...)
```

对应代码在：

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

这说明两件事：

- SSE 里传的不是原始文本，而是业务对象 `GraphNodeResponse`
- 前端收到的不只是“内容”，还会带上 `agentId`、`threadId`、`nodeName`、`textType` 等业务字段

这也是 SSE 在真实项目里最常见的用法：

- 不直接推字符串
- 推结构化对象

## 5. `MediaType.TEXT_EVENT_STREAM_VALUE` 的作用

最基础也最容易漏的一步，是把接口声明成 SSE：

```java
@GetMapping(value = "/stream/search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

作用是告诉 Spring：

- 这不是普通 JSON 接口
- 响应会持续输出
- 编码方式按 `text/event-stream` 处理

没有它，浏览器和 Spring 都不会按 SSE 的协议理解这条响应。

## 6. `ServerSentEvent<T>` 常用 API

这是后端最核心的 SSE 包装类。

最常见写法：

```java
ServerSentEvent.<String>builder()
    .event("message")
    .id("1")
    .data("hello")
    .build();
```

### 6.1 `data(...)`

承载真正的业务数据。

```java
ServerSentEvent.<String>builder()
    .data("hello")
    .build();
```

如果 `T` 是对象，Spring 会自动序列化成 JSON。

DataAgent 里就是这样：

```java
ServerSentEvent.builder(response).build()
```

对应位置：

- [GraphServiceImpl.handleStreamNodeOutput(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

### 6.2 `event(...)`

给事件命名，前端可以按事件名监听。

```java
ServerSentEvent.<String>builder()
    .event("complete")
    .data("done")
    .build();
```

前端：

```html
<script>
  const source = new EventSource("/api/sse/hello");
  source.addEventListener("complete", (event) => {
    console.log("completed:", event.data);
  });
</script>
```

DataAgent 里这点非常关键，它显式发送：

- `STREAM_EVENT_COMPLETE`
- `STREAM_EVENT_ERROR`

对应位置：

- [GraphServiceImpl.handleStreamComplete(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [GraphServiceImpl.handleStreamError(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

这比“单纯断开连接”更稳，因为前端能明确知道这次任务是成功结束还是失败结束。

### 6.3 `id(...)`

给每条事件一个唯一 ID。

```java
ServerSentEvent.<String>builder()
    .id("msg-1001")
    .data("hello")
    .build();
```

常见用途：

- 前端去重
- 断线续传
- 日志排查

DataAgent 当前 `GraphController` 这条 SSE 链路没有用到 `id(...)`，但它是常用 API。

### 6.4 `comment(...)`

发送注释型事件，前端 `EventSource` 一般不会把它当业务消息处理。

典型用法是心跳保活：

```java
ServerSentEvent.<String>builder()
    .comment("heartbeat")
    .build();
```

DataAgent 的会话更新流就用了它：

- [SessionEventPublisher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/chat/SessionEventPublisher.java)

对应代码思路：

```java
Flux.interval(Duration.ofSeconds(2))
    .map(i -> ServerSentEvent.<SessionUpdateEvent>builder()
        .comment("heartbeat")
        .build());
```

这个模式很常见，适合：

- 防止中间代理过早断开连接
- 告诉客户端“连接还活着”

### 6.5 `retry(...)`

告诉客户端断线后建议多久重连。

```java
ServerSentEvent.<String>builder()
    .retry(Duration.ofSeconds(3))
    .data("hello")
    .build();
```

DataAgent 当前没有用它，但它也是 SSE 的常用 API。

## 7. 为什么很多真实项目会用 `Sinks.Many<T>`

最小 demo 里我们直接返回 `Flux.interval(...)` 就够了，但真实项目往往不是“定时器推字符串”，而是：

- 后台工作流异步运行
- 任意时刻都可能产出一段消息
- 产出的线程和 HTTP 请求线程不是同一个

这时就很适合用 `Sinks.Many<T>`。

DataAgent 的代码：

```java
Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink =
    Sinks.many().unicast().onBackpressureBuffer();
```

对应位置：

- [GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

你可以把它理解成：

- `sink` 是“手动往流里塞数据”的入口
- `asFlux()` 是“把这个入口变成前端可订阅的响应流”

## 8. `Sinks.Many<T>` 常用 API

### 8.1 `Sinks.many().unicast().onBackpressureBuffer()`

单播，只允许一个订阅者。

```java
Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
```

适合场景：

- 一个请求对应一个 SSE 连接
- 一个浏览器页面只消费自己的那条流

这正是 DataAgent `GraphController` 的模式。

### 8.2 `Sinks.many().multicast().onBackpressureBuffer()`

多播，允许多个订阅者共享同一个事件源。

```java
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
```

适合场景：

- 一个事件源广播给多个前端连接
- 多个页面同时订阅同一类变更

DataAgent 的会话标题更新流用的是它：

- [SessionEventPublisher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/chat/SessionEventPublisher.java)

### 8.3 `tryEmitNext(...)`

向流里推送一条数据。

```java
Sinks.EmitResult result = sink.tryEmitNext("hello");
```

DataAgent 里最核心的 SSE 推送就是它：

```java
Sinks.EmitResult result = context.getSink()
    .tryEmitNext(ServerSentEvent.builder(response).build());
```

对应位置：

- [GraphServiceImpl.handleStreamNodeOutput(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

### 8.4 `Sinks.EmitResult`

`tryEmitNext(...)` 不阻塞，它会返回发射结果。

典型处理：

```java
Sinks.EmitResult result = sink.tryEmitNext(data);
if (result.isFailure()) {
    // 做清理、重试、降级或记录日志
}
```

DataAgent 的处理方式是：

- 如果发射失败，说明连接大概率已经不可用
- 直接停止对应 `threadId` 的后台流任务

这类清理在长链路项目里很重要，否则后端可能还在继续跑模型和数据库查询。

### 8.5 `tryEmitComplete()`

主动结束这条流。

```java
sink.tryEmitComplete();
```

DataAgent 在两种情况下会这么做：

- 正常完成后，先发一个 `complete` 业务事件，再 `tryEmitComplete()`
- 发生异常后，先发一个 `error` 业务事件，再 `tryEmitComplete()`

这样做的好处是：

- 前端能先收到明确状态
- 连接再以“流完成”的方式结束

### 8.6 `tryEmitError(...)`

直接以错误信号结束 Flux。

```java
sink.tryEmitError(new RuntimeException("boom"));
```

它是常用 API，但在面向前端的 SSE 里要谨慎。

很多项目更喜欢像 DataAgent 这样：

1. 先发一条业务层 `error` 事件
2. 再 `tryEmitComplete()`

原因是浏览器前端通常更容易处理“有结构的错误消息”，而不是只处理一次底层连接异常。

### 8.7 `asFlux()`

把 `sink` 暴露成 `Flux` 返回给前端。

```java
return sink.asFlux();
```

DataAgent 用法：

```java
return sink.asFlux().filter(...).doOnCancel(...);
```

也就是说：

- `sink` 负责生产
- `Flux` 负责声明式加工和返回

## 9. `Flux` 在 SSE 接口里最常用的处理 API

DataAgent 的 `GraphController` 用到了一组非常典型的链式处理。

## 9.1 `filter(...)`

过滤不需要发给前端的事件。

DataAgent 代码思路：

```java
return sink.asFlux().filter(sse -> {
    if (STREAM_EVENT_COMPLETE.equals(sse.event())
            || STREAM_EVENT_ERROR.equals(sse.event())) {
        return true;
    }
    return sse.data() != null
        && sse.data().getText() != null
        && !sse.data().getText().isEmpty();
});
```

对应位置：

- [GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

这个写法很实用：

- 普通空消息不往前端发
- 但 `complete` / `error` 这种状态事件必须保留

### 9.2 `doOnSubscribe(...)`

客户端建立订阅时触发。

```java
.doOnSubscribe(subscription -> log.info("client subscribed"))
```

适合：

- 打日志
- 统计在线连接数
- 初始化上下文

### 9.3 `doOnCancel(...)`

客户端取消订阅时触发。

```java
.doOnCancel(() -> {
    graphService.stopStreamProcessing(threadId);
})
```

这是 SSE 接口里最容易漏掉、但最关键的 API 之一。

浏览器这些操作都可能触发它：

- 关闭页面
- 路由切换
- 手动 `source.close()`
- 网络断开

如果不在这里清理后台任务，后端可能还在继续：

- 调模型
- 查数据库
- 占线程池
- 占内存中的上下文

### 9.4 `doOnError(...)`

流发生异常时触发。

```java
.doOnError(error -> log.error("stream error", error))
```

DataAgent 里它主要做兜底清理，不负责构造业务错误事件。真正的错误 SSE 事件是在服务层显式发送的。

这是一种很稳的职责拆分：

- Controller 负责连接生命周期
- Service 负责业务错误语义

### 9.5 `doOnComplete(...)`

流正常完成时触发。

```java
.doOnComplete(() -> log.info("stream completed"))
```

适合：

- 打完成日志
- 统计耗时
- 做一些收尾动作

### 9.6 `doFinally(...)`

无论是完成、错误还是取消，最后都会走到这里。

```java
.doFinally(signalType -> cleanup(signalType))
```

DataAgent 的 `GraphController` 没直接用它，但 `SessionEventPublisher` 用了：

- [SessionEventPublisher.register(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/chat/SessionEventPublisher.java)

这个 API 很适合做统一回收。

## 10. `ServerSentEvent` 的读取 API

除了构建事件，很多时候你还会在 Flux 里读取它。

DataAgent 在过滤时就用了两个最常见读取方法。

### 10.1 `sse.event()`

读取事件名。

```java
if ("complete".equals(sse.event())) {
    ...
}
```

### 10.2 `sse.data()`

读取业务数据。

```java
GraphNodeResponse body = sse.data();
```

DataAgent 用它判断普通消息是否为空文本：

```java
sse.data() != null
    && sse.data().getText() != null
    && !sse.data().getText().isEmpty()
```

## 11. `ServerHttpResponse` 和响应头为什么常一起出现

DataAgent 在 SSE 接口里额外写了响应头：

```java
response.getHeaders().add("Cache-Control", "no-cache");
response.getHeaders().add("Connection", "keep-alive");
response.getHeaders().add("Access-Control-Allow-Origin", "*");
```

对应位置：

- [GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)

这些头的作用：

- `Cache-Control: no-cache`
  避免代理或浏览器缓存 SSE 响应
- `Connection: keep-alive`
  显式表达长连接意图
- `Access-Control-Allow-Origin: *`
  处理跨域访问

如果你前面有 Nginx，还经常会额外配置：

```text
X-Accel-Buffering: no
```

目的是避免代理缓冲，把本该实时到达的消息攒一批再发。

## 12. 一个更接近真实项目的 SSE 后端例子

下面给一个和 DataAgent 思路接近的写法。

### 12.1 返回对象

```java
public record StreamMessage(
    String taskId,
    String stage,
    String text
) {
}
```

### 12.2 Controller

```java
@RestController
@RequestMapping("/api/sse")
public class TaskSseController {

    private final TaskStreamService taskStreamService;

    public TaskSseController(TaskStreamService taskStreamService) {
        this.taskStreamService = taskStreamService;
    }

    @GetMapping(value = "/task", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamMessage>> streamTask(
            @RequestParam String taskId,
            ServerHttpResponse response) {
        response.getHeaders().add("Cache-Control", "no-cache");
        response.getHeaders().add("Connection", "keep-alive");

        Sinks.Many<ServerSentEvent<StreamMessage>> sink =
            Sinks.many().unicast().onBackpressureBuffer();

        taskStreamService.start(taskId, sink);

        return sink.asFlux()
            .doOnCancel(() -> taskStreamService.stop(taskId))
            .doOnComplete(() -> System.out.println("task completed: " + taskId));
    }

}
```

### 12.3 Service

```java
@Service
public class TaskStreamService {

    private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public void start(String taskId, Sinks.Many<ServerSentEvent<StreamMessage>> sink) {
        Future<?> future = executor.submit(() -> {
            try {
                push(sink, new StreamMessage(taskId, "plan", "开始规划"));
                Thread.sleep(500);

                push(sink, new StreamMessage(taskId, "sql", "正在生成 SQL"));
                Thread.sleep(500);

                push(sink, new StreamMessage(taskId, "result", "查询完成"));

                sink.tryEmitNext(ServerSentEvent.<StreamMessage>builder()
                    .event("complete")
                    .data(new StreamMessage(taskId, "complete", "done"))
                    .build());
                sink.tryEmitComplete();
            }
            catch (Exception e) {
                sink.tryEmitNext(ServerSentEvent.<StreamMessage>builder()
                    .event("error")
                    .data(new StreamMessage(taskId, "error", e.getMessage()))
                    .build());
                sink.tryEmitComplete();
            }
        });
        tasks.put(taskId, future);
    }

    public void stop(String taskId) {
        Future<?> future = tasks.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void push(Sinks.Many<ServerSentEvent<StreamMessage>> sink, StreamMessage message) {
        Sinks.EmitResult result = sink.tryEmitNext(
            ServerSentEvent.<StreamMessage>builder().event("message").data(message).build()
        );
        if (result.isFailure()) {
            throw new IllegalStateException("emit failed: " + result);
        }
    }

}
```

这个例子和 DataAgent 的相似点有：

- Controller 负责建 SSE 通道
- Service 负责异步执行和推流
- 断开连接时要能停后台任务
- 结束时显式发送 `complete` / `error` 事件

## 13. 前端最小验证 demo

虽然你只要求后端，但这段很适合本地联调。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<body>
  <button id="start">开始</button>
  <button id="stop">停止</button>
  <pre id="log"></pre>

  <script>
    let source;
    const log = document.getElementById("log");

    function append(message) {
      log.textContent += message + "\n";
    }

    document.getElementById("start").onclick = () => {
      source = new EventSource("/api/sse/task?taskId=demo-1");

      source.addEventListener("message", (event) => {
        append("message: " + event.data);
      });

      source.addEventListener("complete", (event) => {
        append("complete: " + event.data);
        source.close();
      });

      source.addEventListener("error", (event) => {
        append("error event arrived");
      });

      source.onerror = () => {
        append("connection error");
      };
    };

    document.getElementById("stop").onclick = () => {
      if (source) {
        source.close();
      }
    };
  </script>
</body>
</html>
```

注意两点：

- `EventSource` 原生只支持 GET
- 自定义请求头能力比较弱

所以很多 SSE 接口会设计成 GET，并通过 query 参数传递简单上下文。DataAgent 的 `streamSearch` 也是这个思路。

## 14. DataAgent 这条 SSE 链路是怎么串起来的

按真实代码顺下来，链路是这样的：

### 14.1 Controller 建 SSE 出口

在 [GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java) 里：

1. 声明 `produces = MediaType.TEXT_EVENT_STREAM_VALUE`
2. 设置长连接相关响应头
3. 创建 `Sinks.Many<ServerSentEvent<GraphNodeResponse>>`
4. 调 `graphService.graphStreamProcess(sink, request)`
5. 返回 `sink.asFlux()`
6. 绑定 `filter / doOnSubscribe / doOnCancel / doOnError / doOnComplete`

### 14.2 Service 异步启动 Graph

在 [GraphServiceImpl.graphStreamProcess(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java) 里：

1. 生成或复用 `threadId`
2. 为每个 `threadId` 维护 `StreamContext`
3. 决定是新请求还是人工反馈续跑
4. 拿到 `Flux<NodeOutput>`
5. 异步订阅这个 Flux

### 14.3 节点输出转成 SSE 事件

在 [GraphServiceImpl.handleStreamNodeOutput(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java) 里：

1. 从 `StreamingOutput` 里拿 `node` 和 `chunk`
2. 识别 `textType`
3. 组装 `GraphNodeResponse`
4. `tryEmitNext(ServerSentEvent.builder(response).build())`

### 14.4 完成和异常显式发状态事件

在以下方法里：

- [GraphServiceImpl.handleStreamComplete(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [GraphServiceImpl.handleStreamError(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

做法是：

1. 先发 `event("complete")` 或 `event("error")`
2. 再 `tryEmitComplete()`
3. 再清理上下文

### 14.5 客户端断开时停后台任务

在 [GraphController.streamSearch(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java) 的 `doOnCancel(...)` 里，会调用：

- [GraphServiceImpl.stopStreamProcessing(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)

这一步非常关键，不然 Graph 可能还在后台继续跑。

# 
