package org.example.agent.core.handler;

import org.example.agent.core.AgentLogger;
import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

public final class AsyncEventHandler {

    private AsyncEventHandler() {}

    /**
     * @return 새로 생성한 spanId, txId가 없으면 null
     */
    public static String onStart(String taskName) {
        String txId = TxIdHolder.get();
        if (txId == null) return null;
        String spanId = TraceRuntime.generateSpanId();
        AgentLogger.debug("[RUNTIME] ASYNC START: " + taskName + " (txId=" + txId + ", spanId=" + spanId + ")");
        TcpSender.send(TraceRuntime.buildEvent(txId, TraceEventType.ASYNC_START, TraceCategory.ASYNC,
                taskName, null, true, null, spanId, SpanIdHolder.get()));
        SpanIdHolder.set(spanId);
        return spanId;
    }

    public static void onEnd(String taskName, String spanId, long durationMs) {
        String txId = TxIdHolder.get();
        if (txId == null || spanId == null) return;
        TcpSender.send(TraceRuntime.createRootEvent(txId, TraceEventType.ASYNC_END,
                TraceCategory.ASYNC, taskName, durationMs, true, null, spanId));
    }
}
