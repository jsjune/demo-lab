package org.example.agent.core;

import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.util.HashMap;
import java.util.Map;

class CacheEventHandler {

    static void onGet(String key, boolean hit) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("key", key);
            TcpSender.send(TraceRuntime.createChildEvent(txId,
                    hit ? TraceEventType.CACHE_HIT : TraceEventType.CACHE_MISS,
                    TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    static void onSet(String key) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("key", key);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.CACHE_SET,
                    TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    static void onDel(String key) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("key", key);
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.CACHE_DEL,
                    TraceCategory.CACHE, "redis", null, true, extra));
        });
    }
}
