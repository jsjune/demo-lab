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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
    partitions = 1,
    brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class E2eCacheCategoryEventTest extends E2eCategoryTestBase {

    @Test
    @DisplayName("CACHE 성공: HIT/MISS/SET/DEL 기록")
    void cacheSuccess_shouldRecordHitMissSetDel() throws Exception {
        String txId = newTx("cache-ok");
        callGet("/api/test/cache/success", txId);

        List<TraceEvent> events = await(txId, evs -> hasAllTypes(evs, EnumSet.of(
            TraceEventType.CACHE_HIT,
            TraceEventType.CACHE_MISS,
            TraceEventType.CACHE_SET,
            TraceEventType.CACHE_DEL
        )));
        Map<TraceEventType, List<TraceEvent>> byType = byType(events);

        assertTypeCategoryAndTx(byType.get(TraceEventType.CACHE_HIT), TraceCategory.CACHE, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.CACHE_MISS), TraceCategory.CACHE, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.CACHE_SET), TraceCategory.CACHE, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.CACHE_DEL), TraceCategory.CACHE, txId);
        assertTrue(byType.get(TraceEventType.CACHE_HIT).stream().allMatch(TraceEvent::success));
        assertTrue(byType.get(TraceEventType.CACHE_MISS).stream().allMatch(TraceEvent::success));
        assertTrue(byType.get(TraceEventType.CACHE_SET).stream().allMatch(TraceEvent::success));
        assertTrue(byType.get(TraceEventType.CACHE_DEL).stream().allMatch(TraceEvent::success));
    }

    @Test
    @DisplayName("CACHE 실패(캐시 예외): CACHE_ERROR 실패 + 에러 필드 기록")
    void cacheFailure_shouldRecordCacheError() throws Exception {
        String txId = newTx("cache-fail");
        callGet("/api/test/cache/fail", txId);

        List<TraceEvent> events = await(txId, evs -> {
            Map<TraceEventType, List<TraceEvent>> t = byType(evs);
            return t.containsKey(TraceEventType.CACHE_ERROR)
                && t.get(TraceEventType.CACHE_ERROR).stream().anyMatch(e -> !e.success());
        });

        TraceEvent failed = byType(events).get(TraceEventType.CACHE_ERROR).stream()
            .filter(e -> !e.success())
            .findFirst()
            .orElseThrow();
        assertFalse(failed.success());
        assertTrue(failed.extraInfo().containsKey("errorType"));
        assertTrue(failed.extraInfo().containsKey("errorMessage"));
        assertEquals(TraceCategory.CACHE, failed.category());
    }
}
