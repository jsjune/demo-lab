package org.example.agent.core.handler;

import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

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
        DbEventHandler.resetDepthForTest();
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
        DbEventHandler.resetDepthForTest();
    }

    @Test
    @DisplayName("T-01: onStart — txId 없으면 이벤트 미전송")
    void onStart_txIdNull_noEventEmitted() {
        DbEventHandler.onStart("SELECT 1", "db-host");
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-02: onStart만으로는 이벤트를 전송하지 않는다")
    void onStart_only_noEvent() {
        TxIdHolder.set("tx-001");
        DbEventHandler.onStart("SELECT 1", "db-host");
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-03: onStart/onEnd — DB_QUERY 성공 이벤트 1건 전송")
    void onEnd_emitsSingleSuccessEvent() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        DbEventHandler.onStart("SELECT 1", "db-host");
        DbEventHandler.onEnd("SELECT 1", 42L, "db-host");

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.DB_QUERY, e.type());
        assertEquals(TraceCategory.DB, e.category());
        assertTrue(e.success());
        assertEquals(42L, e.durationMs());
        assertEquals("SELECT 1", e.extraInfo().get("sql"));
    }

    @Test
    @DisplayName("T-04: onError — 실패 이벤트 1건 + 종합 오류 필드 기록")
    void onError_recordsAggregatedFailure() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");
        DbEventHandler.onStart("SELECT X", "db-host");

        DbEventHandler.onError(new IllegalArgumentException("bad-sql"), "SELECT X", 12L, "db-host");

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.DB_QUERY, e.type());
        assertFalse(e.success());
        assertEquals("IllegalArgumentException", e.extraInfo().get("errorType"));
        assertEquals("bad-sql", e.extraInfo().get("errorMessage"));
        assertEquals("java.lang.IllegalArgumentException", e.extraInfo().get("errorClass"));
        assertEquals("java.lang.IllegalArgumentException", e.extraInfo().get("rootCauseClass"));
        assertEquals("bad-sql", e.extraInfo().get("rootCauseMessage"));
        assertTrue(String.valueOf(e.extraInfo().get("chainSummary")).contains("IllegalArgumentException"));
        assertEquals(0, e.extraInfo().get("suppressedCount"));
    }

    @Test
    @DisplayName("T-05: SQLException 체인인 경우 sqlState/vendorCode 기록")
    void onError_recordsSqlExceptionDetails() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");
        DbEventHandler.onStart("SELECT 1", "db-host");

        SQLException sqlException = new SQLException("db down", "08001", 1001);
        RuntimeException wrapped = new RuntimeException("wrapped", sqlException);
        DbEventHandler.onError(wrapped, "SELECT 1", 10L, "db-host");

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertFalse(e.success());
        assertEquals("SQLException", e.extraInfo().get("errorType"));
        assertEquals("db down", e.extraInfo().get("errorMessage"));
        assertEquals("08001", e.extraInfo().get("sqlState"));
        assertEquals("1001", e.extraInfo().get("vendorCode"));
    }

    @Test
    @DisplayName("T-06: blank SQL(connection noise) 성공 케이스는 전송하지 않는다")
    void onStartAndEnd_blankSql_skip() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        DbEventHandler.onStart("   ", "db-host");
        DbEventHandler.onEnd("", 3L, "db-host");

        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-07: blank SQL이어도 에러는 전송한다")
    void onError_blankSql_stillEmits() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");
        DbEventHandler.onStart(" ", "db-host");

        DbEventHandler.onError(new RuntimeException("connection failed"), " ", 5L, "db-host");

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.DB_QUERY, e.type());
        assertFalse(e.success());
        assertNotNull(e.extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-08: 중첩 DB 호출은 최상위 단일 완료 이벤트만 전송")
    void nestedCalls_emitOnlyOneTopLevelEnd() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        DbEventHandler.onStart("SELECT outer", "db-host");
        DbEventHandler.onStart("SELECT inner", "db-host");
        DbEventHandler.onError(new RuntimeException("inner"), "SELECT inner", 1L, "db-host");
        DbEventHandler.onEnd("SELECT outer", 2L, "db-host");

        assertEquals(1, capturedEvents.size());
        TraceEvent end = capturedEvents.get(0);
        assertEquals(TraceEventType.DB_QUERY, end.type());
        assertTrue(end.success());
        assertEquals("SELECT outer", end.extraInfo().get("sql"));
    }
}
