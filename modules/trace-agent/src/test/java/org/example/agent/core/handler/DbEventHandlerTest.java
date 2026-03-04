package org.example.agent.core.handler;

import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: DbEventHandler 단위 테스트")
class DbEventHandlerTest {

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
    @DisplayName("T-01: onStart — txId 없으면 이벤트 미전송")
    void onStart_txIdNull_noEventEmitted() {
        DbEventHandler.onStart("SELECT 1", "db-host");
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-02: onStart — DB_QUERY_START 이벤트 전송")
    void onStart_withTxId_emitsDbQueryStartEvent() {
        TxIdHolder.set("tx-001");
        DbEventHandler.onStart("SELECT 1", "db-host");

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.DB_QUERY_START, e.type());
        assertEquals(TraceCategory.DB, e.category());
        assertEquals("db-host", e.target());
        assertEquals("SELECT 1", e.extraInfo().get("sql"));
    }

    @Test
    @DisplayName("T-03: onEnd — DB_QUERY_END 이벤트 전송, duration 포함")
    void onEnd_emitsDbQueryEndWithDuration() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");
        DbEventHandler.onEnd("SELECT 1", 42L, "db-host");

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.DB_QUERY_END, e.type());
        assertEquals(42L, e.durationMs());
        assertTrue(e.success());
    }

    @Test
    @DisplayName("T-04: onStart — 1001자 SQL은 '...' 접미사로 잘림")
    void onStart_longSql_truncated() {
        TxIdHolder.set("tx-001");
        String longSql = "X".repeat(1001);
        DbEventHandler.onStart(longSql, "db-host");

        assertEquals(1, capturedEvents.size());
        String sql = (String) capturedEvents.get(0).extraInfo().get("sql");
        assertNotNull(sql);
        assertTrue(sql.length() <= 1003, "Truncated sql should be <= 1003 chars");
        assertTrue(sql.endsWith("..."));
    }
}
