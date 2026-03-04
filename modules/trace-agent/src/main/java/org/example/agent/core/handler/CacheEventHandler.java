package org.example.agent.core.handler;

import org.example.agent.core.AgentLogger;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public final class CacheEventHandler {

    private CacheEventHandler() {}

    public static void onGet(String key, boolean hit) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("key", key);
            AgentLogger.debug("[TRACE][CACHE][" + (hit ? "CACHE_HIT" : "CACHE_MISS") + "] txId=" + txId + " key=" + key);
            TcpSender.send(TraceRuntime.createChildEvent(txId,
                    hit ? TraceEventType.CACHE_HIT : TraceEventType.CACHE_MISS,
                    TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    public static void onSet(String key) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("key", key);
            AgentLogger.debug("[TRACE][CACHE][CACHE_SET] txId=" + txId + " key=" + key);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.CACHE_SET,
                    TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    public static void onDel(String key) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("key", key);
            AgentLogger.debug("[TRACE][CACHE][CACHE_DEL] txId=" + txId + " key=" + key);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.CACHE_DEL,
                    TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    public static void onError(Throwable t, String operation, String key) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("operation", operation != null ? operation : "unknown-op");
            extra.put("key", key);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? t.getMessage() : "");
            AgentLogger.debug("[TRACE][CACHE][CACHE_ERROR] txId=" + txId
                + " op=" + extra.get("operation") + " key=" + key
                + " errorType=" + extra.get("errorType")
                + " errorMessage=" + extra.get("errorMessage"));
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.CACHE_ERROR,
                TraceCategory.CACHE, "redis", null, false, extra));
        });
    }

    public static void attachGetListener(Object futureLike, String key) {
        attachCompletionListener(futureLike, "get", key, true);
    }

    public static void attachOpListener(Object futureLike, String operation, String key) {
        attachCompletionListener(futureLike, operation, key, false);
    }

    private static void attachCompletionListener(Object futureLike, String operation, String key, boolean classifyHitMiss) {
        if (futureLike == null) return;
        String capturedTxId = TxIdHolder.get();
        String capturedSpanId = org.example.agent.core.SpanIdHolder.get();
        if (capturedTxId == null) return;
        AgentLogger.debug("[TRACE][CACHE][FLOW] attachCompletionListener txId=" + capturedTxId
            + " op=" + operation + " key=" + key + " classifyHitMiss=" + classifyHitMiss
            + " futureType=" + futureLike.getClass().getName());

        try {
            Method whenComplete = TraceRuntime.findMethod(futureLike.getClass(), "whenComplete", BiConsumer.class);
            if (whenComplete == null) return;

            BiConsumer<Object, Throwable> callback = (result, error) -> {
                String prevTx = TxIdHolder.get();
                String prevSpan = org.example.agent.core.SpanIdHolder.get();
                try {
                    TxIdHolder.set(capturedTxId);
                    org.example.agent.core.SpanIdHolder.set(capturedSpanId);
                    if (error != null) {
                        AgentLogger.debug("[TRACE][CACHE][FLOW] completion error op=" + operation
                            + " key=" + key + " errorType=" + error.getClass().getSimpleName()
                            + " errorMessage=" + (error.getMessage() != null ? error.getMessage() : ""));
                        onError(error, operation, key);
                        return;
                    }
                    AgentLogger.debug("[TRACE][CACHE][FLOW] completion success op=" + operation
                        + " key=" + key + " resultNull=" + (result == null));
                    if (classifyHitMiss) {
                        onGet(key, result != null);
                    } else if ("del".equals(operation)) {
                        onDel(key);
                    } else {
                        onSet(key);
                    }
                } finally {
                    if (prevTx == null) TxIdHolder.clear(); else TxIdHolder.set(prevTx);
                    if (prevSpan == null) org.example.agent.core.SpanIdHolder.clear(); else org.example.agent.core.SpanIdHolder.set(prevSpan);
                }
            };
            whenComplete.invoke(futureLike, callback);
        } catch (Throwable ignored) {
        }
    }
}
