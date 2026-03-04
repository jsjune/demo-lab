package org.example.agent.core.handler;

import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: AsyncEventHandler 단위 테스트")
class AsyncEventHandlerTest {

    private List<TraceEvent> capturedEvents;
    private MockedStatic<TcpSender> tcpMock;

    @BeforeEach
    void setUp() {
        capturedEvents = new ArrayList<>();
        tcpMock = mockStatic(TcpSender.class);
        tcpMock.when(() -> TcpSender.send(any(TraceEvent.class))).thenAnswer(invocation -> {
            capturedEvents.add(invocation.getArgument(0));
            return null;
        });
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @Test
    @DisplayName("T-01: onStart — spanId 반환, SpanIdHolder 갱신, ASYNC_START 전송")
    void onStart_returnsSpanIdAndSetsHolder() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("parent-span");

        String spanId = AsyncEventHandler.onStart("my-task");

        assertNotNull(spanId);
        assertEquals(spanId, SpanIdHolder.get());
        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.ASYNC_START, e.type());
        assertEquals("my-task", e.target());
        // parentSpanId should be the previous spanId
        assertEquals("parent-span", e.parentSpanId());
    }

    @Test
    @DisplayName("T-02: onStart — txId null이면 null 반환, 이벤트 미전송")
    void onStart_txIdNull_returnsNull() {
        String spanId = AsyncEventHandler.onStart("my-task");

        assertNull(spanId);
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-03: onEnd — ASYNC_END 이벤트 전송")
    void onEnd_emitsAsyncEndEvent() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        AsyncEventHandler.onEnd("my-task", "span-001", 50L);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.ASYNC_END, e.type());
        assertEquals(50L, e.durationMs());
        assertEquals("my-task", e.target());
        assertTrue(e.success());
    }

    @Test
    @DisplayName("T-04: onError 후 onEnd — ASYNC_END 실패 + errorType 포함")
    void onError_thenOnEnd_emitsFailureWithErrorType() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        AsyncEventHandler.onError(new RuntimeException("boom"));
        AsyncEventHandler.onEnd("my-task", "span-001", 70L);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.ASYNC_END, e.type());
        assertFalse(e.success());
        assertEquals("RuntimeException", String.valueOf(e.extraInfo().get("errorType")));
        assertEquals("boom", String.valueOf(e.extraInfo().get("errorMessage")));
    }
}
