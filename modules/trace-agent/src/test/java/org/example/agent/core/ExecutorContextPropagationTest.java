package org.example.agent.core;

import org.example.common.TraceEvent;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

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
