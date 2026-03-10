package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.context.TxIdHolder;
import org.example.agent.core.emitter.EventEmitter;
import org.example.agent.core.emitter.TcpSenderEmitter;
import org.example.agent.core.util.AgentLogger;
import org.example.agent.core.handler.AsyncEventHandler;
import org.example.agent.core.handler.CacheEventHandler;
import org.example.agent.core.handler.DbEventHandler;
import org.example.agent.core.handler.HttpEventHandler;
import org.example.agent.core.handler.MqEventHandler;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bytecode ABI entry point for all trace-agent event handlers.
 * All public static methods are injected via INVOKESTATIC — signatures are frozen.
 * Actual logic resides in package-private *EventHandler classes.
 */
public class TraceRuntime {

    public static final String TRACE_MARKER = "__TRACE_TRACKING__";
    public static final String ATTR_TX_ID   = "__TRACE_TX_ID__";
    public static final String ATTR_SPAN_ID = "__TRACE_SPAN_ID__";
    private static final AtomicLong EVENT_SEQ = new AtomicLong(0);
    private static volatile EventEmitter EMITTER = new TcpSenderEmitter();
    private static final ClassValue<java.lang.reflect.Method> GET_ATTR_CACHE = new ClassValue<java.lang.reflect.Method>() {
        @Override
        protected java.lang.reflect.Method computeValue(Class<?> type) {
            return findMethod(type, "getAttribute", String.class);
        }
    };
    private static final ClassValue<java.lang.reflect.Method> SET_ATTR_CACHE = new ClassValue<java.lang.reflect.Method>() {
        @Override
        protected java.lang.reflect.Method computeValue(Class<?> type) {
            return findMethod(type, "setAttribute", String.class, Object.class);
        }
    };

    /** Test hook: replaces the active emitter. Production code uses the default {@link TcpSenderEmitter}. */
    public static void setEmitter(EventEmitter emitter) { EMITTER = emitter; }

    /**
     * Forwards the event to the active {@link EventEmitter}. Pure pass-through — no central deduplication.
     * <p>Design decision: event-type latching is intentionally delegated to each *EventHandler:
     * {@code HttpEventHandler} (HTTP_IN_FLIGHT_TX), {@code DbEventHandler} (DB_CALL_DEPTH),
     * {@code MqEventHandler} (CONSUME_FINISHED). Central latching here would prevent valid
     * multi-emission patterns such as sequential DB_QUERY or HTTP_OUT events.
     */
    public static void emitEvent(TraceEvent event) { EMITTER.emit(event); }

    // ── Lifecycle (위임 없이 유지) ─────────────────────────────────────────
    public static boolean isSecondaryDispatch(Object request) {
        if (request == null) return false;
        try {
            if (Boolean.TRUE.equals(invokeGetAttribute(request, TRACE_MARKER))) return true;
            Object type = invokeMethodSimple(request, "getDispatcherType");
            if (type != null) { String t = type.toString(); return "ASYNC".equals(t) || "ERROR".equals(t); }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isErrorDispatch(Object request) {
        try {
            Object type = invokeMethodSimple(request, "getDispatcherType");
            return type != null && "ERROR".equals(type.toString());
        } catch (Throwable ignored) { return false; }
    }

    public static void restoreContext(Object request) {
        safeRun(() -> {
            String txId = (String) invokeGetAttribute(request, ATTR_TX_ID);
            String sid  = (String) invokeGetAttribute(request, ATTR_SPAN_ID);
            if (txId != null) { TxIdHolder.set(txId); SpanIdHolder.set(sid); AgentLogger.debug("[RUNTIME] Context Restored (Async Resume): txId=" + txId); }
        });
    }

    // ── ABI: HTTP ─────────────────────────────────────────────────────────
    public static void onHttpInStart(Object req, String m, String p, String txId, String sid, boolean f) { HttpEventHandler.onInStart(req, m, p, txId, sid, f); }
    public static void onHttpInEnd(String m, String p, int sc, long ms)                                   { HttpEventHandler.onInEnd(m, p, sc, ms); }
    public static void onHttpInEndAsync(String tx, String sid, String m, String p, long st, Object req)   { HttpEventHandler.onInEndAsync(tx, sid, m, p, st, req, "onComplete", null); }
    public static void onHttpInError(Throwable t, String m, String p, long ms)                            { HttpEventHandler.onInError(t, m, p, ms); }
    public static void registerAsyncListenerFromRequest(Object req)                                        { HttpEventHandler.registerFromRequest(req); }
    public static void registerAsyncListener(Object req, String m, String p, long st)                     { HttpEventHandler.register(req, m, p, st); }
    public static void onHttpOut(String m, String uri, int sc, long ms)                                   { HttpEventHandler.onOut(m, uri, sc, ms); }
    public static void onHttpOutError(Throwable t, String m, String url, long ms)                         { HttpEventHandler.onOutError(t, m, url, ms); }
    public static Object wrapWebClientExchange(Object mono, String m, String uri)                         { return HttpEventHandler.wrapWebClient(mono, m, uri); }
    public static void onWebFluxHandleStart(Object ex)                                                     { HttpEventHandler.onWfStart(ex); }
    public static Object wrapWebFluxHandle(Object mono, Object ex, long t)                                 { return HttpEventHandler.wrapWfHandle(mono, ex, t); }
    public static void onWebFluxHandleSyncError()                                                          { HttpEventHandler.onWfSyncError(); }

    // ── ABI: MQ ───────────────────────────────────────────────────────────
    public static void onMqProduce(String bt, String t, String k)                          { MqEventHandler.onProduce(bt, t, k); }
    public static void onMqConsumeStart(String bt, String t, String id)                    { MqEventHandler.onConsumeStart(bt, t, id); }
    public static void onMqConsumeEnd(String bt, String t, long ms)                        { MqEventHandler.onConsumeEnd(bt, t, ms); }
    public static void onMqConsumeComplete(String bt, String t, long ms)                   { MqEventHandler.onConsumeComplete(bt, t, ms); }
    public static void onMqConsumeErrorMark(Throwable t)                                    { MqEventHandler.markConsumeError(t); }
    public static void onMqConsumeError(Throwable e, String bt, String t, long ms)         { MqEventHandler.onConsumeError(e, bt, t, ms); }

    // ── ABI: DB ───────────────────────────────────────────────────────────
    public static void onDbQueryStart(String sql, String h)                                { DbEventHandler.onStart(sql, h); }
    public static void onDbQueryEnd(String sql, long ms, String h)                         { DbEventHandler.onEnd(sql, ms, h); }
    public static void onDbQueryError(Throwable t, String sql, long ms, String h)          { DbEventHandler.onError(t, sql, ms, h); }

    // ── ABI: Cache ────────────────────────────────────────────────────────
    public static void onCacheGet(String k, boolean hit)                                   { CacheEventHandler.onGet(k, hit); }
    public static void onCacheSet(String k)                                                { CacheEventHandler.onSet(k); }
    public static void onCacheDel(String k)                                                { CacheEventHandler.onDel(k); }
    public static void onCacheError(Throwable t, String op, String key)                    { CacheEventHandler.onError(t, op, key); }
    public static void attachCacheGetListener(Object futureLike, String key)               { CacheEventHandler.attachGetListener(futureLike, key); }
    public static void attachCacheOpListener(Object futureLike, String op, String key)     { CacheEventHandler.attachOpListener(futureLike, op, key); }

    // ── ABI: Async ────────────────────────────────────────────────────────
    public static String onAsyncStart(String task)                                         { return AsyncEventHandler.onStart(task); }
    public static void   onAsyncError(Throwable t)                                          { AsyncEventHandler.onError(t); }
    public static void   onAsyncEnd(String task, String sid, long ms)                      { AsyncEventHandler.onEnd(task, sid, ms); }

    // ── ABI: HTTP utility (status extraction forwarded through ABI) ───────
    /** Extracts HTTP status code from a RestTemplate ResponseEntity-like return value via reflection. */
    public static int extractHttpStatus(Object returnValue)                                { return HttpEventHandler.extractRestTemplateStatus(returnValue); }

    // ── ABI: Reflection utilities ─────────────────────────────────────────
    public static Object invokeGetAttribute(Object target, String name) {
        try {
            if (target == null) return null;
            java.lang.reflect.Method m = GET_ATTR_CACHE.get(target.getClass());
            return m != null ? m.invoke(target, name) : null;
        } catch (Throwable t) { return null; }
    }

    public static void invokeSetAttribute(Object target, String name, Object val) {
        try {
            if (target == null) return;
            java.lang.reflect.Method m = SET_ATTR_CACHE.get(target.getClass());
            if (m != null) m.invoke(target, name, val);
        } catch (Throwable ignored) {}
    }

    public static Object invokeMethodSimple(Object target, String name) {
        try { java.lang.reflect.Method m = findMethod(target.getClass(), name); return m != null ? m.invoke(target) : null; }
        catch (Throwable t) { return null; }
    }

    public static java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try { java.lang.reflect.Method m = current.getDeclaredMethod(name, parameterTypes); m.setAccessible(true); return m; }
            catch (NoSuchMethodException e) { current = current.getSuperclass(); }
        }
        java.util.Set<Class<?>> visited = new java.util.LinkedHashSet<>();
        current = clazz;
        while (current != null && current != Object.class) {
            java.lang.reflect.Method m = findInInterfaces(current.getInterfaces(), name, parameterTypes, visited);
            if (m != null) return m;
            current = current.getSuperclass();
        }
        return null;
    }

    private static java.lang.reflect.Method findInInterfaces(Class<?>[] ifaces, String name, Class<?>[] parameterTypes, java.util.Set<Class<?>> visited) {
        for (Class<?> iface : ifaces) {
            if (!visited.add(iface)) continue;
            try { java.lang.reflect.Method m = iface.getDeclaredMethod(name, parameterTypes); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) {}
            java.lang.reflect.Method m = findInInterfaces(iface.getInterfaces(), name, parameterTypes, visited);
            if (m != null) return m;
        }
        return null;
    }

    public static String safeKeyToString(Object key) {
        if (key == null) return null;
        if (key instanceof byte[]) { byte[] b = (byte[]) key; try { return new String(b, java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { return "[bytes:" + b.length + "]"; } }
        return String.valueOf(key);
    }

    // ── Package-private: 핸들러 공유 팩토리 + 유틸 ───────────────────────
    public static String generateSpanId() {
        return System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public static TraceEvent buildEvent(String txId, TraceEventType type, TraceCategory category,
                                 String target, Long durationMs, boolean success,
                                 Map<String, Object> extra, String spanId, String parentSpanId) {
        return new TraceEvent(AgentConfig.getServerName() + "-" + EVENT_SEQ.incrementAndGet(),
                txId, spanId, parentSpanId, type, category, AgentConfig.getServerName(),
                target, durationMs, success, System.currentTimeMillis(),
                extra != null ? extra : new HashMap<>());
    }

    public static TraceEvent createRootEvent(String txId, TraceEventType type, TraceCategory cat,
                                      String target, Long durationMs, boolean success,
                                      Map<String, Object> extra, String spanId) {
        return buildEvent(txId, type, cat, target, durationMs, success, extra, spanId, null);
    }

    public static TraceEvent createChildEvent(String txId, TraceEventType type, TraceCategory cat,
                                       String target, Long durationMs, boolean success,
                                       Map<String, Object> extra) {
        return buildEvent(txId, type, cat, target, durationMs, success, extra, generateSpanId(), SpanIdHolder.get());
    }

    public static void emit(TraceEventType type, TraceCategory cat, String target,
                     Long durationMs, boolean success, Map<String, Object> extra) {
        safeRun(() -> { String txId = TxIdHolder.get(); if (txId != null) emitEvent(createChildEvent(txId, type, cat, target, durationMs, success, extra)); });
    }

    public static void safeRun(Runnable r) { try { r.run(); } catch (Throwable t) { AgentLogger.error("Runtime error", t); } }
    public static String truncate(String s) { return (s == null || s.length() <= 1000) ? s : s.substring(0, 1000) + "..."; }
}
