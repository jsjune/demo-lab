package org.example.agent.core;

import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.context.TxIdHolder;
import org.example.agent.core.emitter.TcpSender;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@DisplayName("ContextCapturingRunnable Unit Tests")
class ContextCapturingRunnableTest {

    private MockedStatic<TcpSender> tcpMock;

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
        // Prevent actual TCP sends during tests
        tcpMock = mockStatic(TcpSender.class);
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @Test
    @DisplayName("T-01: TxId is restored and NEW SpanId is generated for async task")
    void capturedContext_restoredAndNewSpanGenerated() throws Exception {
        TxIdHolder.set("tx-ccr-01");
        SpanIdHolder.set("parent-span-01");

        AtomicReference<String> seenTx   = new AtomicReference<>();
        AtomicReference<String> seenSpan = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable wrapper = new ContextCapturingRunnable(() -> {
            seenTx.set(TxIdHolder.get());
            seenSpan.set(SpanIdHolder.get());
            latch.countDown();
        });

        Thread t = new Thread(wrapper);
        t.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals("tx-ccr-01", seenTx.get(), "TxId must match parent");
        assertNotNull(seenSpan.get(), "SpanId must not be null");
        assertNotEquals("parent-span-01", seenSpan.get(), 
            "Async task should have its own spanId, different from parent");
    }

    @Test
    @DisplayName("T-02: Holders are cleared after task completion")
    void afterRun_holdersCleared() throws Exception {
        TxIdHolder.set("tx-ccr-02");
        SpanIdHolder.set("span-ccr-02");

        CountDownLatch taskDone = new CountDownLatch(1);
        AtomicReference<String> afterRunTx   = new AtomicReference<>();
        AtomicReference<String> afterRunSpan = new AtomicReference<>();

        Runnable wrapper = new ContextCapturingRunnable(() -> { /* dummy */ });

        Thread t = new Thread(() -> {
            wrapper.run();
            afterRunTx.set(TxIdHolder.get());
            afterRunSpan.set(SpanIdHolder.get());
            taskDone.countDown();
        });
        t.start();
        assertTrue(taskDone.await(5, TimeUnit.SECONDS));

        assertNull(afterRunTx.get(),   "TxIdHolder must be cleared");
        assertNull(afterRunSpan.get(), "SpanIdHolder must be cleared");
    }

    @Test
    @DisplayName("T-03: Remains null if parent has no context")
    void nullContext_remainsNull() throws Exception {
        AtomicReference<String> seenTx = new AtomicReference<>("initial");
        CountDownLatch latch = new CountDownLatch(1);

        Runnable wrapper = new ContextCapturingRunnable(() -> {
            seenTx.set(TxIdHolder.get());
            latch.countDown();
        });

        Thread t = new Thread(wrapper);
        t.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertNull(seenTx.get());
    }

    @Test
    @DisplayName("T-04: Holders are cleared even if task throws exception")
    void exception_finallyClears() throws Exception {
        TxIdHolder.set("tx-ccr-ex");
        SpanIdHolder.set("span-ccr-ex");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> afterTx   = new AtomicReference<>();
        AtomicReference<String> afterSpan = new AtomicReference<>();

        Runnable wrapper = new ContextCapturingRunnable(() -> {
            throw new RuntimeException("intentional");
        });

        Thread t = new Thread(() -> {
            try { wrapper.run(); } catch (RuntimeException ignored) { }
            afterTx.set(TxIdHolder.get());
            afterSpan.set(SpanIdHolder.get());
            latch.countDown();
        });
        t.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertNull(afterTx.get());
        assertNull(afterSpan.get());
    }
}
