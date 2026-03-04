package org.example.agent.core;

import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.util.HashMap;
import java.util.Map;

class MqEventHandler {

    static void onProduce(String brokerType, String topic, String key) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("brokerType", brokerType);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.MQ_PRODUCE,
                    TraceCategory.MQ, topic, null, true, extra));
        });
    }

    static void onConsumeStart(String brokerType, String topic, String incomingTxId) {
        TraceRuntime.safeRun(() -> {
            if (incomingTxId != null && !incomingTxId.isEmpty()) {
                TxIdHolder.set(incomingTxId);
            } else if (TxIdHolder.get() == null) {
                if (!TxIdGenerator.shouldSample()) return;
                TxIdHolder.set(TxIdGenerator.generate());
            }
            String txId = TxIdHolder.get();
            String spanId = TraceRuntime.generateSpanId();
            SpanIdHolder.set(spanId);
            TcpSender.send(TraceRuntime.createRootEvent(txId, TraceEventType.MQ_CONSUME_START,
                    TraceCategory.MQ, topic, null, true, null, spanId));
        });
    }

    static void onConsumeEnd(String brokerType, String topic, long durationMs) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            TcpSender.send(TraceRuntime.createRootEvent(txId, TraceEventType.MQ_CONSUME_END,
                    TraceCategory.MQ, topic, durationMs, true, null, SpanIdHolder.get()));
            TxIdHolder.clear();
            SpanIdHolder.clear();
        });
    }

    static void onConsumeError(Throwable t, String brokerType, String topic, long durationMs) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            TcpSender.send(TraceRuntime.createRootEvent(txId, TraceEventType.MQ_CONSUME_END,
                    TraceCategory.MQ, topic, durationMs, false, null, SpanIdHolder.get()));
            TxIdHolder.clear();
            SpanIdHolder.clear();
        });
    }
}
