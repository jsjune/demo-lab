package org.example.agent.core;

import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

@DisplayName("ContextCapturingCallable Unit Tests")
class ContextCapturingCallableTest {

    private MockedStatic<TcpSender> tcpMock;

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
        tcpMock = mockStatic(TcpSender.class);
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @Test
    @DisplayName("T-01: TxId is restored and NEW SpanId is generated for callable task")
    void capturedContext_restoredAndNewSpanGenerated() throws Exception {
        TxIdHolder.set("tx-ccc-01");
        SpanIdHolder.set("parent-span-ccc-01");

        AtomicReference<String> seenTx   = new AtomicReference<>();
        AtomicReference<String> seenSpan = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ContextCapturingCallable<String> wrapper = new ContextCapturingCallable<>(() -> {
            seenTx.set(TxIdHolder.get());
            seenSpan.set(SpanIdHolder.get());
            latch.countDown();
            return "ok";
        });

        Thread t = new Thread(() -> {
            try { wrapper.call(); } catch (Exception ignored) { }
        });
        t.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals("tx-ccc-01", seenTx.get());
        assertNotNull(seenSpan.get());
        assertNotEquals("parent-span-ccc-01", seenSpan.get(), "Should have a new SpanId");
    }

    @Test
    @DisplayName("T-02: Holders are cleared after call()")
    void afterCall_holdersCleared() throws Exception {
        TxIdHolder.set("tx-ccc-02");
        SpanIdHolder.set("span-ccc-02");

        ContextCapturingCallable<String> wrapper = new ContextCapturingCallable<>(() -> "done");

        AtomicReference<String> afterTx = new AtomicReference<>();
        AtomicReference<String> afterSpan = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            try { wrapper.call(); } catch (Exception ignored) { }
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
