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

@DisplayName("코어: IoEventHandler 단위 테스트")
class IoEventHandlerTest {

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
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @Test
    @DisplayName("T-01: onRead — FILE_READ 이벤트, sizeBytes 포함")
    void onRead_emitsFileReadEvent_withSizeBytes() {
        IoEventHandler.onRead("/tmp/data.txt", 1024L, 10L, true);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.FILE_READ, e.type());
        assertEquals("/tmp/data.txt", e.target());
        assertEquals(1024L, e.extraInfo().get("sizeBytes"));
        assertTrue(e.success());
    }

    @Test
    @DisplayName("T-02: onWrite — FILE_WRITE 이벤트 전송")
    void onWrite_emitsFileWriteEvent() {
        IoEventHandler.onWrite("/tmp/out.log", 2048L, 5L, true);

        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.FILE_WRITE, capturedEvents.get(0).type());
        assertEquals(2048L, capturedEvents.get(0).extraInfo().get("sizeBytes"));
    }

    @Test
    @DisplayName("T-03: onRead(path=null) — target이 'unknown-file'로 대체")
    void onRead_nullPath_fallbackToUnknownFile() {
        IoEventHandler.onRead(null, 0L, 0L, true);

        assertEquals(1, capturedEvents.size());
        assertEquals("unknown-file", capturedEvents.get(0).target());
    }

    @Test
    @DisplayName("T-04: onReadError — 실패 원인(errorType)을 기록해야 한다")
    void onReadError_recordsFailureReason() {
        IoEventHandler.onReadError("/tmp/in.txt", 12L, 2L, new IllegalStateException("io-fail"));

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.FILE_READ, e.type());
        assertFalse(e.success());
        assertEquals("IllegalStateException", e.extraInfo().get("errorType"));
        assertEquals("io-fail", e.extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-05: onWriteError — 실패 원인(errorType)을 기록해야 한다")
    void onWriteError_recordsFailureReason() {
        IoEventHandler.onWriteError("/tmp/out.txt", 20L, 3L, new RuntimeException("io-fail"));

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.FILE_WRITE, e.type());
        assertFalse(e.success());
        assertEquals("RuntimeException", e.extraInfo().get("errorType"));
        assertEquals("io-fail", e.extraInfo().get("errorMessage"));
    }
}
