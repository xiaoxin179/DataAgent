# Flux、Sink、asFlux 的本质理解

## 这份笔记解决什么问题

这份笔记专门解释“持续产生数据”的场景，尤其是：

- SSE 流式响应
- WebFlux 响应式流
- AI token 流
- 前端断开时为什么要清理后端任务

相关的生命周期控制概念可以再配合看 [项目知识总纲](project-knowledge-notes.md)。

## 先抓住本质

`Flux` 不是一个普通返回值，而是一条“未来会陆续产生多条数据”的通道。

普通方法更像这样：

```java
User user = getUser();
```

它返回的是一个已经拿到的结果。

`Flux` 更像这样：

```text
以后可能会不断有数据过来，你先告诉我每条数据来了以后怎么处理。
```

所以它适合处理连续数据，比如：

- SSE 流式响应
- WebSocket 消息
- AI 大模型 token 流式输出
- 实时日志
- 消息队列
- 数据库流式查询

判断什么时候会用到它，只需要问一句：

> 这个结果是一次性返回，还是会一段一段地返回？

如果是一次性结果，通常用普通对象、`List` 或 `Mono`。

如果是持续返回多条数据，通常会考虑 `Flux`。

## Sink 和 Flux 的关系

可以这样记：

```text
Sink：负责往流里发送数据
Flux：负责把流暴露给别人消费
```

也就是：

```text
生产者 -> sink -> Flux -> 消费者
```

在这个项目的 SSE 场景里，后端执行图任务时，会不断产生事件，比如文本片段、完成事件、错误事件。

这些事件先被放进 `sink`：

```java
sink.tryEmitNext(...)
```

然后 Controller 返回：

```java
return sink.asFlux();
```

这表示把 `sink` 转成一个前端可以订阅的响应流。

## asFlux() 到底做了什么

`sink.asFlux()` 的作用是：

```text
把“可以往里发数据的 sink”，转换成“可以被别人订阅消费的 Flux”。
```

它不是马上执行完所有逻辑，也不是马上把所有数据都拿出来。

它只是创建并返回一条流：

```java
sink.asFlux()
```

真正开始工作，是当前端发起 SSE 请求，Spring WebFlux 订阅这个 `Flux` 的时候。

## 为什么后面能点很多方法

因为 `asFlux()` 返回的是 `Flux` 对象，而 `Flux` 提供了很多流式操作符。

这些方法不是随便点出来的，而是在描述一条流水线：

```java
return sink.asFlux()
    .filter(...)
    .doOnCancel(...)
    .doOnError(...)
    .doOnComplete(...);
```

可以理解成：

```text
数据来了
-> 先过滤
-> 如果前端断开，做清理
-> 如果出错，做清理
-> 如果正常完成，记录日志
```

常见操作符可以这样记：

- `filter`：决定这条数据要不要继续传下去
- `map`：把一条数据转换成另一种数据
- `flatMap`：每条数据还要继续触发异步流程
- `doOnCancel`：订阅方取消时执行，比如浏览器断开连接
- `doOnError`：流出错时执行
- `doOnComplete`：流正常结束时执行
- `subscribe`：真正开始消费这条流

## 项目中这段代码的意思

项目中的代码大概是：

```java
return sink.asFlux()
    .filter(sse -> {
        if (STREAM_EVENT_COMPLETE.equals(sse.event()) || STREAM_EVENT_ERROR.equals(sse.event())) {
            return true;
        }
        return sse.data() != null
            && sse.data().getText() != null
            && !sse.data().getText().isEmpty();
    })
    .doOnCancel(() -> {
        if (request.getThreadId() != null) {
            graphService.stopStreamProcessing(request.getThreadId());
        }
    });
```

这段代码本质上是在告诉 Spring：

```text
我返回给前端一条 SSE 流。
每来一个事件，先判断要不要发给前端。
如果前端断开连接，就停止后端任务，避免继续浪费模型、数据库和线程资源。
```

其中 `filter` 的规则是：

- `complete` 和 `error` 事件必须保留，因为前端要靠它们判断任务结束
- 普通事件如果没有文本内容，就过滤掉，避免前端出现空白 token

## 不要死记 API

学习这类代码时，不要先背方法名。

应该先看它表达的业务模型：

```text
这是一次性结果，还是连续结果？
谁负责生产数据？
谁负责消费数据？
中间要不要过滤、转换、监听取消、监听错误？
```

只要你能看出这是“连续数据流”，再看到 `Flux`、`Sink`、`filter`、`doOnCancel` 就不会觉得突兀。

## 一句话总结

`Sink` 是数据入口，`Flux` 是数据流出口，`asFlux()` 是把入口包装成可订阅的流；后面的链式方法是在描述“每条数据来了以后怎么处理，以及流取消、出错、完成时做什么”。
