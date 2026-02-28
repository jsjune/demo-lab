package org.example.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.TraceEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.TraceEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TraceConsumerService {

    private final TraceRepository traceRepository;
    private final KafkaTemplate<String, TraceEvent> kafkaTemplate;
    private final BatchProperties properties;
    private final MeterRegistry meterRegistry;
    private final List<TraceEvent> buffer = new CopyOnWriteArrayList<>();

    public TraceConsumerService(TraceRepository traceRepository, 
                                KafkaTemplate<String, TraceEvent> kafkaTemplate, 
                                BatchProperties properties, 
                                MeterRegistry meterRegistry) {
        this.traceRepository = traceRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "${trace.batch.topic:trace-events}", groupId = "trace-batch-group", concurrency = "3")
    public void consume(TraceEvent event) {
        meterRegistry.counter("trace.batch.events.received").increment();
        buffer.add(event);
        if (buffer.size() >= properties.getBatchSize()) {
            flush();
        }
    }

    @Scheduled(fixedDelayString = "${trace.batch.flush-interval-ms:5000}")
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    private synchronized void flush() {
        if (buffer.isEmpty()) return;
        
        List<TraceEvent> toSave = new ArrayList<>(buffer);
        buffer.clear();
        
        long start = System.nanoTime();
        try {
            traceRepository.saveAll(toSave);
            long duration = System.nanoTime() - start;
            meterRegistry.timer("trace.batch.db.write.latency").record(duration, TimeUnit.NANOSECONDS);
            meterRegistry.counter("trace.batch.events.persisted").increment(toSave.size());
        } catch (Exception e) {
            log.error("[TRACE BATCH] Failed to flush batch to DB. Sending to DLQ. Error: {}", e.getMessage());
            meterRegistry.counter("trace.batch.events.failed").increment(toSave.size());
            sendToDlq(toSave);
        }
    }

    private void sendToDlq(List<TraceEvent> failedEvents) {
        String dlqTopic = properties.getTopic() + "-dlq";
        for (TraceEvent event : failedEvents) {
            kafkaTemplate.send(dlqTopic, event.txId(), event);
        }
        log.warn("[TRACE BATCH] Forwarded {} events to DLQ topic: {}", failedEvents.size(), dlqTopic);
    }
}
