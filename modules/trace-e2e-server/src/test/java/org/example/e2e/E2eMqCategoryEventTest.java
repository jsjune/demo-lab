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
class E2eMqCategoryEventTest extends E2eCategoryTestBase {

    @Test
    @DisplayName("MQ 성공: MQ_PRODUCE/MQ_CONSUME_START/MQ_CONSUME_END 기록")
    void mqSuccess_shouldRecordProduceConsumeEvents() throws Exception {
        String txId = newTx("mq-ok");
        callGet("/api/test/mq/success", txId);

        List<TraceEvent> events = await(txId, evs -> hasAllTypes(evs, EnumSet.of(
            TraceEventType.MQ_PRODUCE, TraceEventType.MQ_CONSUME_START, TraceEventType.MQ_CONSUME_END
        )));
        Map<TraceEventType, List<TraceEvent>> byType = byType(events);

        assertTypeCategoryAndTx(byType.get(TraceEventType.MQ_PRODUCE), TraceCategory.MQ, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.MQ_CONSUME_START), TraceCategory.MQ, txId);
        assertTypeCategoryAndTx(byType.get(TraceEventType.MQ_CONSUME_END), TraceCategory.MQ, txId);
        assertTrue(byType.get(TraceEventType.MQ_CONSUME_END).stream().allMatch(TraceEvent::success));
    }

    @Test
    @DisplayName("MQ 실패(consume 예외): MQ_CONSUME_END 실패 + 에러 필드 기록")
    void mqFailure_shouldRecordConsumeEndFailureWithError() throws Exception {
        String txId = newTx("mq-fail");
        callGet("/api/test/mq/fail", txId);

        List<TraceEvent> events = await(txId, evs -> {
            Map<TraceEventType, List<TraceEvent>> t = byType(evs);
            return t.containsKey(TraceEventType.MQ_PRODUCE)
                && t.containsKey(TraceEventType.MQ_CONSUME_START)
                && t.containsKey(TraceEventType.MQ_CONSUME_END)
                && t.get(TraceEventType.MQ_CONSUME_END).stream().anyMatch(e -> !e.success());
        });

        TraceEvent failed = byType(events).get(TraceEventType.MQ_CONSUME_END).stream()
            .filter(e -> !e.success())
            .findFirst()
            .orElseThrow();
        assertTrue(failed.extraInfo().containsKey("errorType"));
        assertTrue(failed.extraInfo().containsKey("errorMessage"));
    }
}
