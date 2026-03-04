package org.example.e2e;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
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
class E2eHttpCategoryEventTest extends E2eCategoryTestBase {

    @Test
    @DisplayName("HTTP 성공: HTTP_IN_START/HTTP_OUT/HTTP_IN_END 기록")
    void httpSuccess_shouldRecordThreeHttpEvents() throws Exception {
        String txId = newTx("http-ok");
        callGet("/api/test/http/success", txId);

        List<TraceEvent> events = await(txId, evs -> hasAllTypes(evs, EnumSet.of(
            TraceEventType.HTTP_IN_START, TraceEventType.HTTP_OUT, TraceEventType.HTTP_IN_END
        )));
        Map<TraceEventType, List<TraceEvent>> byType = byType(events);

        assertTypeCategoryAndTx(byType.get(TraceEventType.HTTP_IN_START), TraceCategory.HTTP, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.HTTP_OUT), TraceCategory.HTTP, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.HTTP_IN_END), TraceCategory.HTTP, txId);
        assertTrue(byType.get(TraceEventType.HTTP_OUT).stream().allMatch(TraceEvent::success));
        assertTrue(byType.get(TraceEventType.HTTP_IN_END).stream().allMatch(TraceEvent::success));
    }

    @Test
    @DisplayName("HTTP 실패(아웃바운드 예외): HTTP_OUT 실패 + 에러 필드 기록")
    void httpOutboundFailure_shouldRecordHttpOutFailureWithError() throws Exception {
        String txId = newTx("http-out-fail");
        callGet("/api/test/http/out-fail", txId);

        List<TraceEvent> events = await(txId, evs -> {
            Map<TraceEventType, List<TraceEvent>> t = byType(evs);
            return t.containsKey(TraceEventType.HTTP_IN_START)
                && t.containsKey(TraceEventType.HTTP_IN_END)
                && t.containsKey(TraceEventType.HTTP_OUT)
                && t.get(TraceEventType.HTTP_OUT).stream().anyMatch(e -> !e.success());
        });

        List<TraceEvent> outEvents = byType(events).get(TraceEventType.HTTP_OUT);
        TraceEvent failed = outEvents.stream().filter(e -> !e.success()).findFirst().orElseThrow();
        assertTrue(failed.extraInfo().containsKey("errorType"));
        assertTrue(failed.extraInfo().containsKey("errorMessage"));
    }

    @Test
    @DisplayName("HTTP 실패(인바운드 예외): HTTP_IN_END 실패 + 에러 필드 기록")
    void httpInboundFailure_shouldRecordHttpInEndFailureWithError() throws Exception {
        String txId = newTx("http-in-fail");
        ResponseEntity<String> response = callGet("/api/test/http/in-fail", txId);
        assertTrue(response.getStatusCode().is5xxServerError());

        List<TraceEvent> events = await(txId, evs -> {
            Map<TraceEventType, List<TraceEvent>> t = byType(evs);
            return t.containsKey(TraceEventType.HTTP_IN_START)
                && t.containsKey(TraceEventType.HTTP_IN_END)
                && t.get(TraceEventType.HTTP_IN_END).stream().anyMatch(e -> !e.success());
        });

        TraceEvent failed = byType(events).get(TraceEventType.HTTP_IN_END).stream()
            .filter(e -> !e.success())
            .findFirst()
            .orElseThrow();
        assertTrue(failed.extraInfo().containsKey("errorType"));
        assertTrue(failed.extraInfo().containsKey("errorMessage"));
    }
}
