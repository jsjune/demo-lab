package org.example.e2e;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
    partitions = 1,
    brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class E2eDbCategoryEventTest extends E2eCategoryTestBase {

    @Test
    @DisplayName("DB 성공: DB_QUERY(성공) 1건 기록")
    void dbSuccess_shouldRecordSingleEnd() throws Exception {
        String txId = newTx("db-ok");
        callGet("/api/test/db/success", txId);

        TraceEvent end = awaitSingleDbEnd(txId, true);
        assertEquals(TraceEventType.DB_QUERY, end.type());
        assertEquals(TraceCategory.DB, end.category());
        assertEquals(txId, end.txId());
        assertTrue(end.success());
        assertValidDbPayload(end, true);
    }

    @Test
    @DisplayName("DB 실패(쿼리 예외): DB_QUERY(실패) 1건 + 에러 필드 기록")
    void dbFailure_shouldRecordSingleFailedEndWithError() throws Exception {
        String txId = newTx("db-fail");
        callGet("/api/test/db/fail", txId);

        TraceEvent failed = awaitSingleDbEnd(txId, false);
        assertEquals(TraceCategory.DB, failed.category());
        assertEquals(txId, failed.txId());
        assertFalse(failed.success());
        assertValidDbPayload(failed, true);
        assertAggregatedDbErrorFields(failed);

        String sql = String.valueOf(failed.extraInfo().get("sql")).toLowerCase();
        assertTrue(sql.contains("cast"), "Expected SQL snippet 'cast' in sql='" + sql + "'");
        String errorMessage = errorMessageOf(failed);
        assertTrue(errorMessage.contains("data conversion"), "Expected 'data conversion' in errorMessage='" + errorMessage + "'");
        assertTrue(errorMessage.contains("not-a-number"), "Expected 'not-a-number' in errorMessage='" + errorMessage + "'");
    }

    @Test
    @DisplayName("DB 실패(Statement 오류): DB_QUERY(실패) 1건 + 에러 필드 기록")
    void dbFailure_statementSyntax_shouldRecordSingleFailedEndWithError() throws Exception {
        String txId = newTx("db-fail-stmt");
        callGet("/api/test/db/fail-statement-syntax", txId);

        TraceEvent failed = awaitSingleDbEnd(txId, false);
        assertEquals(TraceCategory.DB, failed.category());
        assertEquals(txId, failed.txId());
        assertFalse(failed.success());
        assertValidDbPayload(failed, true);
        assertAggregatedDbErrorFields(failed);

        String sql = String.valueOf(failed.extraInfo().get("sql")).toLowerCase();
        assertTrue(sql.contains("tinyint"), "Expected SQL snippet 'tinyint' in sql='" + sql + "'");
        String errorMessage = errorMessageOf(failed);
        assertTrue(errorMessage.contains("out of range"), "Expected 'out of range' in errorMessage='" + errorMessage + "'");
    }

    @Test
    @DisplayName("DB 실패(Prepare 오류): DB_QUERY(실패) 1건 + 에러 필드 기록")
    void dbFailure_prepareSyntax_shouldRecordSingleFailedEndWithError() throws Exception {
        String txId = newTx("db-fail-prepare");
        callGet("/api/test/db/fail-prepare-syntax", txId);

        TraceEvent failed = awaitSingleDbEnd(txId, false);
        assertEquals(TraceCategory.DB, failed.category());
        assertEquals(txId, failed.txId());
        assertFalse(failed.success());
        assertValidDbPayload(failed, true);
        assertAggregatedDbErrorFields(failed);

        String sql = String.valueOf(failed.extraInfo().get("sql")).toLowerCase();
        assertTrue(sql.contains("/ 0"), "Expected SQL snippet '/ 0' in sql='" + sql + "'");
        String errorMessage = errorMessageOf(failed);
        assertTrue(errorMessage.contains("division by zero") || errorMessage.contains("/ by zero"),
            "Expected division-by-zero message in errorMessage='" + errorMessage + "'");
    }

    private TraceEvent awaitSingleDbEnd(String txId, boolean success) throws Exception {
        List<TraceEvent> events = await(txId, evs -> {
            Map<TraceEventType, List<TraceEvent>> byType = byType(evs);
            List<TraceEvent> dbEnds = byType.getOrDefault(TraceEventType.DB_QUERY, List.of()).stream()
                .filter(e -> e.success() == success)
                .toList();
            return dbEnds.size() == 1;
        });
        List<TraceEvent> dbEnds = byType(events).getOrDefault(TraceEventType.DB_QUERY, List.of()).stream()
            .filter(e -> e.success() == success)
            .toList();
        assertEquals(1, dbEnds.size(), "Expected exactly one DB_QUERY(success=" + success + "), events=" + events.stream().map(TraceEvent::type).toList());
        return dbEnds.get(0);
    }

    private void assertValidDbPayload(TraceEvent event, boolean requiresDuration) {
        assertNotNull(event.target());
        assertFalse(event.target().isBlank());
        if (requiresDuration) {
            assertNotNull(event.durationMs());
            assertTrue(event.durationMs() >= 0L);
        }
        assertTrue(event.extraInfo().containsKey("sql"));
        Object sql = event.extraInfo().get("sql");
        assertNotNull(sql);
        assertFalse(String.valueOf(sql).isBlank());
    }

    private String errorMessageOf(TraceEvent event) {
        Object message = event.extraInfo().get("errorMessage");
        assertNotNull(message);
        return String.valueOf(message).toLowerCase();
    }

    private void assertAggregatedDbErrorFields(TraceEvent failed) {
        assertTrue(failed.extraInfo().containsKey("errorType"));
        assertTrue(failed.extraInfo().containsKey("errorMessage"));
        assertTrue(failed.extraInfo().containsKey("errorClass"));
        assertTrue(failed.extraInfo().containsKey("rootCauseClass"));
        assertTrue(failed.extraInfo().containsKey("rootCauseMessage"));
        assertTrue(failed.extraInfo().containsKey("chainSummary"));
        assertTrue(failed.extraInfo().containsKey("suppressedCount"));
        assertTrue(failed.extraInfo().containsKey("sqlState"));
        assertTrue(failed.extraInfo().containsKey("vendorCode"));
        assertFalse(String.valueOf(failed.extraInfo().get("errorType")).isBlank());
        assertFalse(String.valueOf(failed.extraInfo().get("errorClass")).isBlank());
        assertFalse(String.valueOf(failed.extraInfo().get("rootCauseClass")).isBlank());
        assertFalse(String.valueOf(failed.extraInfo().get("chainSummary")).isBlank());
    }
}
