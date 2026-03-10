package org.example.agent.core.handler;

import org.example.agent.core.util.AgentLogger;
import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.context.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AsyncEventHandler {

    private AsyncEventHandler() {}
    private static final ThreadLocal<Throwable> ASYNC_ERROR = new ThreadLocal<>();

    /**
     * @return 새로 생성한 spanId, txId가 없으면 null
     */
    public static String onStart(String taskName) {
        String txId = TxIdHolder.get();
        if (txId == null) return null;
        ASYNC_ERROR.remove();
        String spanId = TraceRuntime.generateSpanId();
        AgentLogger.debug("[RUNTIME] ASYNC START: " + taskName + " (txId=" + txId + ", spanId=" + spanId + ")");
        TraceRuntime.emitEvent(TraceRuntime.buildEvent(txId, TraceEventType.ASYNC_START, TraceCategory.ASYNC,
                taskName, null, true, null, spanId, SpanIdHolder.get()));
        SpanIdHolder.set(spanId);
        return spanId;
    }

    public static void onError(Throwable t) {
        AgentLogger.debug("[TRACE][ASYNC][FLOW] markError type="
            + (t != null ? t.getClass().getSimpleName() : "UnknownError")
            + " message=" + (t != null && t.getMessage() != null ? t.getMessage() : ""));
        ASYNC_ERROR.set(t);
    }

    public static void onEnd(String taskName, String spanId, long durationMs) {
        String txId = TxIdHolder.get();
        if (txId == null || spanId == null) return;
        Throwable error = ASYNC_ERROR.get();
        ASYNC_ERROR.remove();

        Map<String, Object> extra = null;
        boolean success = error == null;
        if (!success) {
            extra = new LinkedHashMap<>();
            extra.put("errorType", error.getClass().getSimpleName());
            extra.put("errorMessage", error.getMessage() != null ? error.getMessage() : "");
        }
        AgentLogger.debug("[TRACE][ASYNC][ASYNC_END] txId=" + txId
            + " spanId=" + spanId + " task=" + taskName + " durationMs=" + durationMs
            + " success=" + success
            + (success ? "" : " errorType=" + extra.get("errorType") + " errorMessage=" + extra.get("errorMessage")));

        TraceRuntime.emitEvent(TraceRuntime.createRootEvent(txId, TraceEventType.ASYNC_END,
                TraceCategory.ASYNC, taskName, durationMs, success, extra, spanId));
    }
}
