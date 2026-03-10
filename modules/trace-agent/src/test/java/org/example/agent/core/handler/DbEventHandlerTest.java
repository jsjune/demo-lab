package org.example.agent.core.handler;

import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.emitter.TcpSender;
import org.example.agent.core.context.TxIdHolder;
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
        DbEventHandler.resetForTest();
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
        DbEventHandler.resetForTest();
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
    @DisplayName("T-08: 중첩 DB 호출은 최상위 단일 완료 이벤트만 전송 (inner error + outer success)")
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

    @Test
    @DisplayName("T-09: 중첩 inner success + outer success → 외부 SQL 이벤트 1건만")
    void nestedCalls_innerSuccess_outerSuccess_onlyOuterEventEmitted() {
        TxIdHolder.set("tx-002");
        SpanIdHolder.set("span-002");

        DbEventHandler.onStart("SELECT outer", "db-host");
        DbEventHandler.onStart("SELECT inner", "db-host");
        DbEventHandler.onEnd("SELECT inner", 5L, "db-host");   // inner success — must be suppressed
        DbEventHandler.onEnd("SELECT outer", 50L, "db-host");  // outer success — must emit

        assertEquals(1, capturedEvents.size(), "Only outermost call should emit DB_QUERY");
        TraceEvent e = capturedEvents.get(0);
        assertTrue(e.success());
        assertEquals("SELECT outer", e.extraInfo().get("sql"));
        assertEquals(50L, e.durationMs());
    }

    @Test
    @DisplayName("T-10: 다중 inner 호출 중 일부 성공/일부 오류 — 외부 성공 이벤트 1건만")
    void multipleNestedQueries_onlyOuterEventEmitted() {
        TxIdHolder.set("tx-003");
        SpanIdHolder.set("span-003");

        DbEventHandler.onStart("SELECT outer", "db-host");
        DbEventHandler.onStart("SELECT inner-1", "db-host");
        DbEventHandler.onEnd("SELECT inner-1", 3L, "db-host");          // success — suppressed
        DbEventHandler.onStart("SELECT inner-2", "db-host");
        DbEventHandler.onError(new RuntimeException("err"), "SELECT inner-2", 2L, "db-host"); // error — suppressed
        DbEventHandler.onEnd("SELECT outer", 100L, "db-host");          // outer success — emitted

        assertEquals(1, capturedEvents.size(), "Only outermost call should emit DB_QUERY");
        TraceEvent e = capturedEvents.get(0);
        assertTrue(e.success());
        assertEquals("SELECT outer", e.extraInfo().get("sql"));
    }

    @Test
    @DisplayName("T-11: 연속 독립 쿼리 — 각각 이벤트 1건씩 총 2건 전송")
    void sequentialIndependentQueries_eachEmitsOneEvent() {
        TxIdHolder.set("tx-004");
        SpanIdHolder.set("span-004");

        DbEventHandler.onStart("SELECT first", "db-host");
        DbEventHandler.onEnd("SELECT first", 10L, "db-host");

        DbEventHandler.onStart("SELECT second", "db-host");
        DbEventHandler.onEnd("SELECT second", 20L, "db-host");

        assertEquals(2, capturedEvents.size(), "Each independent query should emit its own DB_QUERY");
        assertEquals("SELECT first", capturedEvents.get(0).extraInfo().get("sql"));
        assertEquals("SELECT second", capturedEvents.get(1).extraInfo().get("sql"));
    }
}
