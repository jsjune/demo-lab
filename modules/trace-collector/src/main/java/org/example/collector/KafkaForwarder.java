package org.example.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.TraceEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaForwarder {

    private final KafkaTemplate<String, TraceEvent> kafkaTemplate;
    private final CollectorProperties properties;

    public void forward(TraceEvent event) {
        kafkaTemplate.send(properties.getKafka().getTopic(), event.txId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("[TRACE COLLECTOR] Kafka forwarding failed for txId {}: {}", event.txId(), ex.getMessage());
                } else {
                    log.debug("[TRACE COLLECTOR] Successfully forwarded txId {} to Kafka", event.txId());
                }
            });
    }
}
