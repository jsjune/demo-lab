package org.example.e2e;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.util.EnumSet;
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
    @DisplayName("DB 성공: DB_QUERY_START/DB_QUERY_END 기록")
    void dbSuccess_shouldRecordStartEnd() throws Exception {
        String txId = newTx("db-ok");
        callGet("/api/test/db/success", txId);

        List<TraceEvent> events = await(txId, evs -> hasAllTypes(evs, EnumSet.of(
            TraceEventType.DB_QUERY_START, TraceEventType.DB_QUERY_END
        )));
        Map<TraceEventType, List<TraceEvent>> byType = byType(events);

        assertTypeCategoryAndTx(byType.get(TraceEventType.DB_QUERY_START), TraceCategory.DB, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.DB_QUERY_END), TraceCategory.DB, txId);
        assertTrue(byType.get(TraceEventType.DB_QUERY_START).stream().allMatch(TraceEvent::success));
        assertTrue(byType.get(TraceEventType.DB_QUERY_END).stream().allMatch(TraceEvent::success));

        TraceEvent start = byType.get(TraceEventType.DB_QUERY_START).get(0);
        TraceEvent end = byType.get(TraceEventType.DB_QUERY_END).stream()
            .filter(TraceEvent::success)
            .findFirst()
            .orElseThrow();
        assertValidDbPayload(start, false);
        assertValidDbPayload(end, true);
        assertEquals(start.extraInfo().get("sql"), end.extraInfo().get("sql"));
        assertTrue(end.timestamp() >= start.timestamp());
    }

    @Test
    @DisplayName("DB 실패(쿼리 예외): DB_QUERY_END 실패 + 에러 필드 기록")
    void dbFailure_shouldRecordEndFailureWithError() throws Exception {
        String txId = newTx("db-fail");
        callGet("/api/test/db/fail", txId);

        List<TraceEvent> events = await(txId, evs -> {
            Map<TraceEventType, List<TraceEvent>> t = byType(evs);
            return t.containsKey(TraceEventType.DB_QUERY_START)
                && t.containsKey(TraceEventType.DB_QUERY_END)
                && t.get(TraceEventType.DB_QUERY_END).stream().anyMatch(e -> !e.success());
        });

        Map<TraceEventType, List<TraceEvent>> grouped = byType(events);
        assertNotNull(grouped.get(TraceEventType.DB_QUERY_START),
            "DB_QUERY_START not observed for txId=" + txId + " events="
                + events.stream().map(TraceEvent::type).toList());
        assertNotNull(grouped.get(TraceEventType.DB_QUERY_END),
            "DB_QUERY_END not observed for txId=" + txId + " events="
                + events.stream().map(TraceEvent::type).toList());

        TraceEvent start = grouped.get(TraceEventType.DB_QUERY_START).get(0);
        TraceEvent failed = grouped.get(TraceEventType.DB_QUERY_END).stream()
            .filter(e -> !e.success())
            .findFirst()
            .orElseThrow();
        assertValidDbPayload(start, false);
        assertValidDbPayload(failed, true);
        assertEquals(TraceCategory.DB, failed.category());
        assertEquals(txId, failed.txId());
        assertFalse(failed.success());
        assertTrue(failed.timestamp() >= start.timestamp());
        assertEquals(start.extraInfo().get("sql"), failed.extraInfo().get("sql"));
        assertTrue(failed.extraInfo().containsKey("errorType"));
        assertTrue(failed.extraInfo().containsKey("errorMessage"));
        assertFalse(String.valueOf(failed.extraInfo().get("errorType")).isBlank());
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
}
