/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.graph;

// GraphRequest 是流式图请求对象，测试 graphStreamProcess 时会用到。
import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
// MultiTurnContextManager 负责多轮上下文管理，这里用 mock 隔离其真实逻辑。
import com.alibaba.cloud.ai.dataagent.service.graph.Context.MultiTurnContextManager;
// LangfuseService 负责埋点和链路追踪，这里用 mock 避免接触真实 tracing 后端。
import com.alibaba.cloud.ai.dataagent.service.langfuse.LangfuseService;
// GraphNodeResponse 是 SSE 输出的数据载体，测试流式接口时需要它。
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
// CompiledGraph 是已经编译好的图执行对象，这里用 mock 控制 invoke/stream 行为。
import com.alibaba.cloud.ai.graph.CompiledGraph;
// OverAllState 是图执行完成后的整体状态对象。
import com.alibaba.cloud.ai.graph.OverAllState;
// RunnableConfig 是图执行的运行时配置。
import com.alibaba.cloud.ai.graph.RunnableConfig;
// StateGraph 这里只用于构造 GraphServiceImpl 时创建一个可编译的占位对象。
import com.alibaba.cloud.ai.graph.StateGraph;
// GraphRunnerException 是图执行过程中可能抛出的异常类型。
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
// Span 是 OpenTelemetry 的追踪对象，这里用 mock 代替真实 span。
import io.opentelemetry.api.trace.Span;
// BeforeEach 表示每个测试方法执行前都要先做一次初始化。
import org.junit.jupiter.api.BeforeEach;
// Test 表示这是一个单元测试方法。
import org.junit.jupiter.api.Test;
// ExtendWith 用来启用 Mockito 的 JUnit 5 扩展。
import org.junit.jupiter.api.extension.ExtendWith;
// Mock 用来声明 Mockito mock 对象。
import org.mockito.Mock;
// MockitoExtension 负责在 JUnit 运行时自动创建和注入 mock 对象。
import org.mockito.junit.jupiter.MockitoExtension;
// MockitoSettings 用于配置 Mockito 的严格程度。
import org.mockito.junit.jupiter.MockitoSettings;
// Strictness 控制 Mockito 对多余桩代码等问题的检查力度。
import org.mockito.quality.Strictness;
// ServerSentEvent 是流式接口返回的 SSE 包装类型。
import org.springframework.http.codec.ServerSentEvent;
// Flux 是 Reactor 的响应式流类型。
import reactor.core.publisher.Flux;
// Sinks 用来手动向 Flux 推送事件。
import reactor.core.publisher.Sinks;

// 反射需要用到的 Field 类型。
import java.lang.reflect.Field;
// Optional 用于承载可能为空的图执行结果。
import java.util.Optional;
// ExecutorService 是图服务内部使用的线程池接口。
import java.util.concurrent.ExecutorService;
// Executors 用于快速创建测试用线程池。
import java.util.concurrent.Executors;

// 断言方法，帮助我们判断测试是否符合预期。
import static org.junit.jupiter.api.Assertions.*;
// Mockito 的参数匹配器和桩代码方法。
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GraphServiceImpl 的单元测试。
 *
 * <p>
 * 这组测试的核心思路是：不真的跑完整图流程，而是把依赖全部 mock 掉，
 * 只验证 GraphServiceImpl 自己的“编排逻辑”是否正确。
 * </p>
 */
// 启用 Mockito 对 JUnit 5 的支持。
@ExtendWith(MockitoExtension.class)
// 允许 Mockito 的 lenient 模式，减少一些非关键测试场景下的严格报错。
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphServiceImplTest {

	// 用 mock 替代真实的 CompiledGraph，避免测试真的执行图工作流。
	@Mock
	private CompiledGraph compiledGraph;

	// 用 mock 替代多轮上下文管理器。
	@Mock
	private MultiTurnContextManager multiTurnContextManager;

	// 用 mock 替代 Langfuse 埋点服务。
	@Mock
	private LangfuseService langfuseReporter;

	// 用 mock 替代 tracing span。
	@Mock
	private Span mockSpan;

	// 被测对象，真正要验证的是它内部的协调逻辑。
	private GraphServiceImpl graphService;

	// 测试专用线程池，模拟服务内部的异步执行环境。
	private ExecutorService executor;

	/**
	 * 每个测试方法执行前先初始化测试环境。
	 *
	 * <p>
	 * 这里统一创建线程池、构造 GraphServiceImpl，并给常用 mock 预设默认行为。
	 * </p>
	 */
	@BeforeEach
	void setUp() throws Exception {
		// 创建一个单线程线程池，减少测试并发带来的不确定性。
		executor = Executors.newSingleThreadExecutor();

		// 先准备一个可被编译的 StateGraph 占位对象。
		StateGraph mockStateGraph = mock(StateGraph.class);
		// 让 stateGraph.compile(...) 返回我们提前准备好的 compiledGraph mock。
		when(mockStateGraph.compile(any())).thenReturn(compiledGraph);

		// 构造被测对象，把依赖都注入进去。
		graphService = new GraphServiceImpl(mockStateGraph, executor, multiTurnContextManager, langfuseReporter);

		// 某些版本或构造路径下，直接反射替换 compiledGraph，确保后续调用都落到 mock 上。
		setField(graphService, "compiledGraph", compiledGraph);

		// 默认情况下，开始一个 LLM span 时返回 mockSpan。
		when(langfuseReporter.startLLMSpan(anyString(), any())).thenReturn(mockSpan);
		// 默认让 span 处于可记录状态。
		when(mockSpan.isRecording()).thenReturn(true);
		// 默认给多轮上下文一个空值标记，避免测试依赖真实历史数据。
		when(multiTurnContextManager.buildContext(anyString())).thenReturn("(无)");
	}

	/**
	 * 用反射给对象的私有字段赋值。
	 *
	 * <p>
	 * 这是测试里常见的“补刀”手段：当构造器注入不够灵活，或者你想强制替换内部字段时会用到。
	 * </p>
	 */
	private void setField(Object target, String fieldName, Object value) throws Exception {
		// 找到目标对象上的指定字段。
		Field field = target.getClass().getDeclaredField(fieldName);
		// 关闭 Java 访问检查，允许操作 private 字段。
		field.setAccessible(true);
		// 把新的值塞进去。
		field.set(target, value);
	}

	/**
	 * 验证 nl2sql 正常执行时，可以把图执行结果中的 SQL 正确返回出来。
	 */
	@Test
	void nl2sql_validQuery_returnsResult() throws GraphRunnerException {
		// 这里直接使用真实的 OverAllState 对象，不再 mock final 类。
		// 只要把 SQL 结果放进 state 里，就能验证 nl2sql 是否能正确取出来。
		OverAllState state = new OverAllState(java.util.Map.of("SQL_GENERATE_OUTPUT", "SELECT * FROM users"));
		// 让 compiledGraph.invoke(...) 返回上面准备好的状态对象，并打印调用过程。
		when(compiledGraph.invoke(anyMap(), any(RunnableConfig.class))).thenAnswer(invocation -> {
			System.out.println("compiledGraph.invoke 被调用");
			System.out.println("入参 map: " + invocation.getArgument(0));
			System.out.println("入参 config: " + invocation.getArgument(1));
			return Optional.of(state);
		});
		System.out.println("-------");
		System.out.println(state.data());


		System.out.println("开始执行 nl2sql_validQuery_returnsResult");
		// 调用被测方法。
		String result = graphService.nl2sql("show all users", "1");
		System.out.println("nl2sql 返回结果: " + result);

		// 断言最终 SQL 正确返回。
		assertEquals("SELECT * FROM users", result);
		// 验证确实调用了图执行接口。
		verify(compiledGraph).invoke(anyMap(), any(RunnableConfig.class));
	}

	/**
	 * 验证当图里没有生成 SQL 时，方法能返回空字符串。
	 */
	@Test
	void nl2sql_emptyResult_returnsEmptyString() throws GraphRunnerException {
		// 这里同样使用真实对象，只是不放 SQL 字段。
		OverAllState state = new OverAllState(java.util.Map.of());
		// 图执行仍然返回一个有效状态，只是里面没有 SQL。
		when(compiledGraph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.of(state));

		// 调用被测方法。
		String result = graphService.nl2sql("invalid query", "1");

		// 断言返回空字符串。
		assertEquals("", result);
	}

	/**
	 * 验证流式请求在没有 threadId 时，会自动补一个新的 threadId。
	 */
	@Test
	void graphStreamProcess_newProcess_setsThreadIdIfMissing() {
		// 构造一个不带 threadId 的请求。
		GraphRequest request = GraphRequest.builder().agentId("1").query("test query").build();

		// 创建一个流式 sink，用于接收 SSE 数据。
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().multicast().onBackpressureBuffer();

		// 让图流式执行时返回空流，避免真的进入复杂链路。
		when(compiledGraph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());

		// 调用流式处理。
		graphService.graphStreamProcess(sink, request);

		// 断言 threadId 已经被自动补上。
		assertNotNull(request.getThreadId());
		// 断言 threadId 不是空字符串。
		assertFalse(request.getThreadId().isEmpty());
	}

	/**
	 * 验证如果请求本身已经带了 threadId，服务不会擅自改掉它。
	 */
	@Test
	void graphStreamProcess_withThreadId_usesExistingThreadId() {
		// 构造一个已经带 threadId 的请求。
		GraphRequest request = GraphRequest.builder()
			.agentId("1")
			.threadId("existing-thread")
			.query("test query")
			.build();

		// 创建 sink。
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().multicast().onBackpressureBuffer();

		// 图执行返回空流。
		when(compiledGraph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());

		// 执行流式处理。
		graphService.graphStreamProcess(sink, request);

		// 断言 threadId 保持不变。
		assertEquals("existing-thread", request.getThreadId());
	}

	/**
	 * 验证 stopStreamProcessing 对空 threadId 是幂等且安全的。
	 */
	@Test
	void stopStreamProcessing_nullThreadId_doesNothing() {
		// 空参数不应抛异常。
		assertDoesNotThrow(() -> graphService.stopStreamProcessing(null));
		// 空字符串也不应抛异常。
		assertDoesNotThrow(() -> graphService.stopStreamProcessing(""));
	}

	/**
	 * 验证当 threadId 找不到对应上下文时，停止流程也不会崩。
	 */
	@Test
	void stopStreamProcessing_unknownThread_doesNothing() {
		// 不应抛异常。
		assertDoesNotThrow(() -> graphService.stopStreamProcessing("unknown-thread"));
		// 但 discardPending 仍然应该被调用，用来清理多轮上下文。
		verify(multiTurnContextManager).discardPending("unknown-thread");
	}

	/**
	 * 验证已有活跃流时，停止处理会触发清理逻辑。
	 */
	@Test
	void stopStreamProcessing_existingThread_cleansUp() {
		// 准备一个有 threadId 的请求。
		GraphRequest request = GraphRequest.builder()
			.agentId("1")
			.threadId("thread-to-stop")
			.query("test query")
			.build();

		// 创建 sink。
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().multicast().onBackpressureBuffer();
		// 让图执行返回永不结束的流，便于模拟“正在进行中”的状态。
		when(compiledGraph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.never());

		// 启动流式处理。
		graphService.graphStreamProcess(sink, request);

		// 给异步订阅一点点时间。
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			// 如果线程被打断，恢复中断标志位。
			Thread.currentThread().interrupt();
		}

		// 主动停止处理。
		graphService.stopStreamProcessing("thread-to-stop");
		// 断言上下文清理动作发生了。
		verify(multiTurnContextManager).discardPending("thread-to-stop");
	}

	/**
	 * 验证 nl2sql 在图执行抛异常时，会把异常继续抛出。
	 */
	@Test
	void nl2sql_graphRunnerException_throwsException() {
		// 让图执行直接抛运行时异常。
		when(compiledGraph.invoke(anyMap(), any(RunnableConfig.class)))
			.thenThrow(new RuntimeException("Graph execution failed"));

		// 断言调用方法时会抛出 RuntimeException。
		assertThrows(RuntimeException.class, () -> graphService.nl2sql("test", "1"));
	}

	/**
	 * 验证当图执行返回 Optional.empty() 时，方法会走异常路径。
	 */
	@Test
	void nl2sql_emptyOptional_returnsEmpty() throws GraphRunnerException {
		// 让图执行返回空 Optional。
		when(compiledGraph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.empty());

		// 这里预期会抛异常，因为 orElseThrow() 会失败。
		assertThrows(Exception.class, () -> graphService.nl2sql("test", "1"));
	}

}
