package org.example.agent.core;

import org.example.agent.core.context.AsyncTaskNameHolder;
import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.context.TxIdHolder;
import org.example.agent.core.emitter.TcpSender;
import org.example.agent.core.emitter.TcpSenderEmitter;
import org.example.common.TraceEvent;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@DisplayName("Component: ContextCapturingRunnable context propagation")
class ExecutorContextPropagationTest {

    private MockedStatic<TcpSender> tcpMock;

    @BeforeEach
    void setup() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
        tcpMock = mockStatic(TcpSender.class);
        tcpMock.when(() -> TcpSender.send(any(TraceEvent.class))).thenAnswer(inv -> null);
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
        AsyncTaskNameHolder.clear();
    }

    @Test
    @DisplayName("AsyncTaskNameHolder에 이름이 있으면 ASYNC_START target에 실제 메소드명이 기록된다")
    void contextCapturingRunnable_usesTaskNameFromHolder() throws Exception {
        List<TraceEvent> captured = new ArrayList<>();
        TraceRuntime.setEmitter(captured::add);
        try {
            TxIdHolder.set("tx-taskname-01");
            SpanIdHolder.set("span-taskname-01");
            AsyncTaskNameHolder.set("UserService.processOrder");

            CountDownLatch latch = new CountDownLatch(1);
            new ContextCapturingRunnable(() -> latch.countDown()).run();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            TraceEvent asyncStart = captured.stream()
                .filter(e -> e.type().name().equals("ASYNC_START"))
                .findFirst().orElse(null);
            assertNotNull(asyncStart, "ASYNC_START event should be emitted");
            assertEquals("UserService.processOrder", asyncStart.target());
        } finally {
            TraceRuntime.setEmitter(new TcpSenderEmitter());
            TxIdHolder.clear(); SpanIdHolder.clear();
        }
    }

    @Test
    @DisplayName("AsyncTaskNameHolder가 비어 있으면 'Async-Runnable' fallback 사용")
    void contextCapturingRunnable_fallsBackToDefaultName_whenHolderEmpty() throws Exception {
        List<TraceEvent> captured = new ArrayList<>();
        TraceRuntime.setEmitter(captured::add);
        try {
            TxIdHolder.set("tx-fallback-01");
            SpanIdHolder.set("span-fallback-01");

            CountDownLatch latch = new CountDownLatch(1);
            new ContextCapturingRunnable(() -> latch.countDown()).run();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            TraceEvent asyncStart = captured.stream()
                .filter(e -> e.type().name().equals("ASYNC_START"))
                .findFirst().orElse(null);
            assertNotNull(asyncStart);
            assertEquals("Async-Runnable", asyncStart.target());
        } finally {
            TraceRuntime.setEmitter(new TcpSenderEmitter());
            TxIdHolder.clear(); SpanIdHolder.clear();
        }
    }

    @Test
    @DisplayName("TxId propagates via ContextCapturingRunnable and NEW SpanId is set")
    void propagateViaWrapper() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            TxIdHolder.set("tx-exec-01");
            SpanIdHolder.set("parent-span-exec-01");

            AtomicReference<String> seenTx = new AtomicReference<>();
            AtomicReference<String> seenSpan = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            // In a real agent scenario, ExecutorPlugin wraps this automatically.
            // In unit/integration tests without the full agent, we wrap it manually to test logic.
            Runnable task = new ContextCapturingRunnable(() -> {
                seenTx.set(TxIdHolder.get());
                seenSpan.set(SpanIdHolder.get());
                latch.countDown();
            });

            executor.execute(task);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("tx-exec-01", seenTx.get());
            assertNotNull(seenSpan.get());
            assertNotEquals("parent-span-exec-01", seenSpan.get(), 
                "Async task must have its own SpanId generated via onAsyncStart");
        } finally {
            executor.shutdownNow();
        }
    }
}
