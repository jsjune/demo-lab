package org.example.agent.core;

import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.util.LinkedHashMap;
import java.util.Map;

class IoEventHandler {

    static void onRead(String path, long sizeBytes, long durationMs, boolean success) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.FILE_READ,
                    TraceCategory.IO, path != null ? path : "unknown-file", durationMs, success, extra));
        });
    }

    static void onWrite(String path, long sizeBytes, long durationMs, boolean success) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.FILE_WRITE,
                    TraceCategory.IO, path != null ? path : "unknown-file", durationMs, success, extra));
        });
    }
}
