package org.example.agent.core.handler;

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
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.DB_QUERY_END,
                    TraceCategory.DB, dbHost, durationMs, false, extra));
        });
    }
}
