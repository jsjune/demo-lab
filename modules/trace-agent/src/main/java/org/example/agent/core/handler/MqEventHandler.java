package org.example.agent.core.handler;

import org.example.agent.core.util.AgentLogger;
import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.util.TxIdGenerator;
import org.example.agent.core.context.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MqEventHandler {

    private MqEventHandler() {}
    private static final ThreadLocal<Throwable> CONSUME_ERROR = new ThreadLocal<>();
    private static final ThreadLocal<Long> CONSUME_START_MS = new ThreadLocal<>();
    private static final ThreadLocal<String> CONSUME_TOPIC = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CONSUME_FINISHED = new ThreadLocal<>();

    public static void onProduce(String brokerType, String topic, String key) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("brokerType", brokerType);
            AgentLogger.debug("[TRACE][MQ][MQ_PRODUCE] txId=" + txId
                + " brokerType=" + brokerType + " topic=" + topic + " key=" + key);
            TraceRuntime.emitEvent(TraceRuntime.createChildEvent(txId, TraceEventType.MQ_PRODUCE,
                    TraceCategory.MQ, topic, null, true, extra));
        });
    }

    public static void onConsumeStart(String brokerType, String topic, String incomingTxId) {
        TraceRuntime.safeRun(() -> {
            CONSUME_ERROR.remove();
            if (incomingTxId != null && !incomingTxId.isEmpty()) {
                TxIdHolder.set(incomingTxId);
            } else if (TxIdHolder.get() == null) {
                if (!TxIdGenerator.shouldSample()) return;
                TxIdHolder.set(TxIdGenerator.generate());
            }
            String txId = TxIdHolder.get();
            String spanId = TraceRuntime.generateSpanId();
            SpanIdHolder.set(spanId);
            CONSUME_START_MS.set(System.currentTimeMillis());
            CONSUME_TOPIC.set(topic);
            CONSUME_FINISHED.set(Boolean.FALSE);
            AgentLogger.debug("[TRACE][MQ][MQ_CONSUME_START] txId=" + txId
                + " brokerType=" + brokerType + " topic=" + topic + " incomingTxId=" + incomingTxId
                + " spanId=" + spanId);
            TraceRuntime.emitEvent(TraceRuntime.createRootEvent(txId, TraceEventType.MQ_CONSUME_START,
                    TraceCategory.MQ, topic, null, true, null, spanId));
        });
    }

    public static void onConsumeEnd(String brokerType, String topic, long durationMs) {
        TraceRuntime.safeRun(() -> {
            if (Boolean.TRUE.equals(CONSUME_FINISHED.get())) return;
            String txId = TxIdHolder.get();
            if (txId == null) return;
            String resolvedTopic = topic != null ? topic : CONSUME_TOPIC.get();
            long resolvedDuration = resolveDuration(durationMs);
            AgentLogger.debug("[TRACE][MQ][MQ_CONSUME_END] txId=" + txId
                + " brokerType=" + brokerType + " topic=" + resolvedTopic + " durationMs=" + resolvedDuration
                + " success=true");
            TraceRuntime.emitEvent(TraceRuntime.createRootEvent(txId, TraceEventType.MQ_CONSUME_END,
                    TraceCategory.MQ, resolvedTopic, resolvedDuration, true, null, SpanIdHolder.get()));
            clearConsumeContext();
        });
    }

    public static void markConsumeError(Throwable t) {
        AgentLogger.debug("[TRACE][MQ][FLOW] markConsumeError type="
            + (t != null ? t.getClass().getSimpleName() : "UnknownError")
            + " message=" + (t != null && t.getMessage() != null ? t.getMessage() : ""));
        CONSUME_ERROR.set(t);
    }

    public static void onConsumeComplete(String brokerType, String topic, long durationMs) {
        TraceRuntime.safeRun(() -> {
            if (Boolean.TRUE.equals(CONSUME_FINISHED.get())) return;
            Throwable marked = CONSUME_ERROR.get();
            AgentLogger.debug("[TRACE][MQ][FLOW] onConsumeComplete brokerType=" + brokerType
                + " topic=" + topic + " durationMs=" + durationMs + " markedError=" + (marked != null));
            if (marked != null) {
                onConsumeError(marked, brokerType, topic, durationMs);
                return;
            }
            onConsumeEnd(brokerType, topic, durationMs);
        });
    }

    public static void onConsumeError(Throwable t, String brokerType, String topic, long durationMs) {
        TraceRuntime.safeRun(() -> {
            if (Boolean.TRUE.equals(CONSUME_FINISHED.get())) return;
            String txId = TxIdHolder.get();
            if (txId == null) return;
            String resolvedTopic = topic != null ? topic : CONSUME_TOPIC.get();
            long resolvedDuration = resolveDuration(durationMs);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("brokerType", brokerType);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? t.getMessage() : "");
            AgentLogger.debug("[TRACE][MQ][MQ_CONSUME_END] txId=" + txId
                + " brokerType=" + brokerType + " topic=" + resolvedTopic + " durationMs=" + resolvedDuration
                + " success=false errorType=" + extra.get("errorType")
                + " errorMessage=" + extra.get("errorMessage"));
            TraceRuntime.emitEvent(TraceRuntime.createRootEvent(txId, TraceEventType.MQ_CONSUME_END,
                    TraceCategory.MQ, resolvedTopic, resolvedDuration, false, extra, SpanIdHolder.get()));
            clearConsumeContext();
        });
    }

    private static long resolveDuration(long durationMs) {
        if (durationMs >= 0) return durationMs;
        Long start = CONSUME_START_MS.get();
        return start != null ? (System.currentTimeMillis() - start) : 0L;
    }

    private static void clearConsumeContext() {
        CONSUME_FINISHED.set(Boolean.TRUE);
        CONSUME_ERROR.remove();
        CONSUME_START_MS.remove();
        CONSUME_TOPIC.remove();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    /** Test-only reset — clears all ThreadLocals including CONSUME_FINISHED. */
    static void resetForTest() {
        CONSUME_FINISHED.remove();
        CONSUME_ERROR.remove();
        CONSUME_START_MS.remove();
        CONSUME_TOPIC.remove();
    }
}
