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

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
    partitions = 1,
    brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class E2eAsyncCategoryEventTest extends E2eCategoryTestBase {

    @Test
    @DisplayName("ASYNC 성공: ASYNC_START/ASYNC_END 기록")
    void asyncSuccess_shouldRecordStartEnd() throws Exception {
        String txId = newTx("async-ok");
        callGet("/api/test/async/success", txId);

        List<TraceEvent> events = await(txId, evs -> hasAllTypes(evs, EnumSet.of(
            TraceEventType.ASYNC_START, TraceEventType.ASYNC_END
        )));
        Map<TraceEventType, List<TraceEvent>> byType = byType(events);

        assertTypeCategoryAndTx(byType.get(TraceEventType.ASYNC_START), TraceCategory.ASYNC, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.ASYNC_END), TraceCategory.ASYNC, txId);
        assertTrue(byType.get(TraceEventType.ASYNC_END).stream().allMatch(TraceEvent::success));
    }

    @Test
    @DisplayName("ASYNC 실패(작업 예외): ASYNC_END 실패 + 에러 필드 기록")
    void asyncFailure_shouldRecordEndFailureWithError() throws Exception {
        String txId = newTx("async-fail");
        callGet("/api/test/async/fail", txId);

        List<TraceEvent> events = await(txId, evs -> {
            Map<TraceEventType, List<TraceEvent>> t = byType(evs);
            return t.containsKey(TraceEventType.ASYNC_START)
                && t.containsKey(TraceEventType.ASYNC_END)
                && t.get(TraceEventType.ASYNC_END).stream().anyMatch(e -> !e.success());
        });

        TraceEvent failed = byType(events).get(TraceEventType.ASYNC_END).stream()
            .filter(e -> !e.success())
            .findFirst()
            .orElseThrow();
        assertTrue(failed.extraInfo().containsKey("errorType"));
        assertTrue(failed.extraInfo().containsKey("errorMessage"));
    }
}
