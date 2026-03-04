package org.example.agent.core;

import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: HttpEventHandler 단위 테스트")
class HttpEventHandlerTest {

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
    @DisplayName("T-01: onInEnd — 2xx statusCode → success=true, extra[statusCode] 포함")
    void onInEnd_statusCode2xx_successTrue() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        HttpEventHandler.onInEnd("GET", "/api", 200, 10L);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.HTTP_IN_END, e.type());
        assertTrue(e.success());
        assertEquals(200, e.extraInfo().get("statusCode"));
        assertNull(TxIdHolder.get());
        assertNull(SpanIdHolder.get());
    }

    @Test
    @DisplayName("T-02: onInEnd — 5xx statusCode → success=false")
    void onInEnd_statusCode5xx_successFalse() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        HttpEventHandler.onInEnd("GET", "/api", 500, 10L);

        assertEquals(1, capturedEvents.size());
        assertFalse(capturedEvents.get(0).success());
        assertEquals(500, capturedEvents.get(0).extraInfo().get("statusCode"));
    }

    @Test
    @DisplayName("T-03: onWfSyncError — TxIdHolder, SpanIdHolder 모두 null로 클리어")
    void onWfSyncError_clearsContext() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        HttpEventHandler.onWfSyncError();

        assertNull(TxIdHolder.get());
        assertNull(SpanIdHolder.get());
    }
}