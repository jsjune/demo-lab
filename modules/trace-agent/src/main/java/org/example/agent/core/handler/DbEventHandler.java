package org.example.agent.core.handler;

import org.example.agent.core.AgentLogger;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DbEventHandler {

    private DbEventHandler() {}

    public static void onStart(String sql, String dbHost) {
        TraceRuntime.safeRun(() -> {
            Map<String, Object> extra = new HashMap<>();
            extra.put("sql", TraceRuntime.truncate(sql));
            AgentLogger.debug("[TRACE][DB][DB_QUERY_START] txId=" + TxIdHolder.get()
                + " dbHost=" + dbHost + " sql=" + TraceRuntime.truncate(sql));
            TraceRuntime.emit(TraceEventType.DB_QUERY_START, TraceCategory.DB,
                    dbHost != null ? dbHost : "unknown-db", null, true, extra);
        });
    }

    public static void onEnd(String sql, long durationMs, String dbHost) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("sql", TraceRuntime.truncate(sql));
            AgentLogger.debug("[TRACE][DB][DB_QUERY_END] txId=" + txId
                + " dbHost=" + dbHost + " durationMs=" + durationMs + " success=true");
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.DB_QUERY_END,
                    TraceCategory.DB, dbHost, durationMs, true, extra));
        });
    }

    public static void onError(Throwable t, String sql, long durationMs, String dbHost) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sql", TraceRuntime.truncate(sql));
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? t.getMessage() : "");
            AgentLogger.debug("[TRACE][DB][DB_QUERY_END] txId=" + txId
                + " dbHost=" + dbHost + " durationMs=" + durationMs + " success=false"
                + " errorType=" + extra.get("errorType")
                + " errorMessage=" + extra.get("errorMessage"));
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.DB_QUERY_END,
                    TraceCategory.DB, dbHost, durationMs, false, extra));
        });
    }
}
