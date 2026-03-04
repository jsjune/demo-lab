package org.example.agent.core.handler;

import org.example.agent.core.AgentLogger;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.util.LinkedHashMap;
import java.util.Map;

public final class IoEventHandler {

    private IoEventHandler() {}

    public static void onRead(String path, long sizeBytes, long durationMs, boolean success) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            AgentLogger.debug("[TRACE][IO][FILE_READ] txId=" + txId + " path=" + path
                + " sizeBytes=" + sizeBytes + " durationMs=" + durationMs + " success=" + success);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.FILE_READ,
                    TraceCategory.IO, path != null ? path : "unknown-file", durationMs, success, extra));
        });
    }

    public static void onReadError(String path, long sizeBytes, long durationMs, Throwable t) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? t.getMessage() : "");
            AgentLogger.debug("[TRACE][IO][FILE_READ] txId=" + txId + " path=" + path
                + " sizeBytes=" + sizeBytes + " durationMs=" + durationMs + " success=false"
                + " errorType=" + extra.get("errorType")
                + " errorMessage=" + extra.get("errorMessage"));
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.FILE_READ,
                TraceCategory.IO, path != null ? path : "unknown-file", durationMs, false, extra));
        });
    }

    public static void onWrite(String path, long sizeBytes, long durationMs, boolean success) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            AgentLogger.debug("[TRACE][IO][FILE_WRITE] txId=" + txId + " path=" + path
                + " sizeBytes=" + sizeBytes + " durationMs=" + durationMs + " success=" + success);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.FILE_WRITE,
                    TraceCategory.IO, path != null ? path : "unknown-file", durationMs, success, extra));
        });
    }

    public static void onWriteError(String path, long sizeBytes, long durationMs, Throwable t) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? t.getMessage() : "");
            AgentLogger.debug("[TRACE][IO][FILE_WRITE] txId=" + txId + " path=" + path
                + " sizeBytes=" + sizeBytes + " durationMs=" + durationMs + " success=false"
                + " errorType=" + extra.get("errorType")
                + " errorMessage=" + extra.get("errorMessage"));
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.FILE_WRITE,
                TraceCategory.IO, path != null ? path : "unknown-file", durationMs, false, extra));
        });
    }
}
