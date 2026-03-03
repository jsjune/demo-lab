package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class TraceRuntime {
    private static final AtomicLong EVENT_SEQ = new AtomicLong(0);

    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        GET_ATTR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        HTTP_STATUS_CODE_METHOD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        HTTP_STATUS_VALUE_METHOD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        WC_EXCEPTION_STATUS_METHOD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        WF_GET_REQUEST_CACHE  = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        WF_GET_RESPONSE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        WF_REQ_METHOD_CACHE   = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        WF_REQ_URI_CACHE      = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        WF_REQ_HEADERS_CACHE  = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        WF_RESP_STATUS_CACHE  = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Highly robust check to prevent duplicate transaction IDs on Async Resume or Error Dispatch.
     */
    public static boolean shouldSkipTracking(Object request) {
        try {
            ClassLoader cl = request.getClass().getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            
            // 1. Check DispatcherType (Standard Servlet way)
            // Use interface classes to ensure method visibility
            String[] requestInterfaces = { "jakarta.servlet.ServletRequest", "javax.servlet.ServletRequest" };
            for (String iface : requestInterfaces) {
                try {
                    Class<?> reqClass = Class.forName(iface, false, cl);
                    if (reqClass.isInstance(request)) {
                        java.lang.reflect.Method gdt = reqClass.getMethod("getDispatcherType");
                        Object type = gdt.invoke(request);
                        if (type != null) {
                            String name = type.toString();
                            if ("ASYNC".equals(name) || "ERROR".equals(name)) return true;
                        }
                        break; 
                    }
                } catch (Throwable ignored) {}
            }

            // 2. Fallback: Check Spring-specific WebAsyncManager attribute
            // If hasConcurrentResult is true, this is a resume dispatch.
            for (String iface : requestInterfaces) {
                try {
                    Class<?> reqClass = Class.forName(iface, false, cl);
                    if (reqClass.isInstance(request)) {
                        java.lang.reflect.Method getAttr = reqClass.getMethod("getAttribute", String.class);
                        Object manager = getAttr.invoke(request, "org.springframework.web.context.request.async.WebAsyncManager.WEB_ASYNC_MANAGER");
                        if (manager != null) {
                            java.lang.reflect.Method hasResult = manager.getClass().getMethod("hasConcurrentResult");
                            if (Boolean.TRUE.equals(hasResult.invoke(manager))) return true;
                        }
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void onHttpInStart(String method, String path, String incomingTxId, String incomingSpanId, boolean forceTrace) {
        safeRun(() -> {
            if (incomingTxId != null && !incomingTxId.isEmpty()) {
                TxIdHolder.set(incomingTxId);
            } else if (TxIdHolder.get() == null) {
                if (!forceTrace && !TxIdGenerator.shouldSample()) return;
                TxIdHolder.set(TxIdGenerator.generate());
            }
            
            String txId = TxIdHolder.get();
            if (txId != null) {
                String spanId = generateSpanId();
                SpanIdHolder.set(spanId);
                TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
                        method + " " + path, null, true, null, spanId, incomingSpanId));
            }
        });
    }

    public static void onHttpInEnd(String method, String path, int statusCode, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            String spanId = SpanIdHolder.get();
            boolean success = statusCode >= 200 && statusCode < 400;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", statusCode);
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP,
                    method + " " + path, durationMs, success, extra, spanId, null));
            TxIdHolder.clear();
            SpanIdHolder.clear();
        });
    }

    public static void onHttpInError(Throwable t, String method, String path, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            String spanId = SpanIdHolder.get();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP,
                    method + " " + path, durationMs, false, extra, spanId, null));
            TxIdHolder.clear();
            SpanIdHolder.clear();
        });
    }

    public static void onHttpOut(String method, String uri, int statusCode, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("statusCode", statusCode);
            extra.put("method", method);
            TcpSender.send(createChildEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, uri, durationMs,
                    statusCode >= 200 && statusCode < 400, extra));
        });
    }

    public static void onHttpOutError(Throwable t, String method, String url, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            TcpSender.send(createChildEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, url, durationMs, false, extra));
        });
    }

    public static void onMqProduce(String brokerType, String topic, String key) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("brokerType", brokerType);
            TcpSender.send(createChildEvent(txId, TraceEventType.MQ_PRODUCE, TraceCategory.MQ, topic, null, true, extra));
        });
    }

    public static void onMqConsumeStart(String brokerType, String topic, String incomingTxId) {
        safeRun(() -> {
            if (incomingTxId != null && !incomingTxId.isEmpty()) TxIdHolder.set(incomingTxId);
            else if (TxIdHolder.get() == null) {
                if (!TxIdGenerator.shouldSample()) return;
                TxIdHolder.set(TxIdGenerator.generate());
            }
            String txId = TxIdHolder.get();
            String spanId = generateSpanId();
            SpanIdHolder.set(spanId);
            TcpSender.send(createRootEvent(txId, TraceEventType.MQ_CONSUME_START, TraceCategory.MQ, topic, null, true, null, spanId));
        });
    }

    public static void onMqConsumeEnd(String brokerType, String topic, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            TcpSender.send(createRootEvent(txId, TraceEventType.MQ_CONSUME_END, TraceCategory.MQ, topic, durationMs, true, null, SpanIdHolder.get()));
            TxIdHolder.clear();
            SpanIdHolder.clear();
        });
    }

    public static void onMqConsumeError(Throwable t, String brokerType, String topic, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            TcpSender.send(createRootEvent(txId, TraceEventType.MQ_CONSUME_END, TraceCategory.MQ, topic, durationMs, false, null, SpanIdHolder.get()));
            TxIdHolder.clear();
            SpanIdHolder.clear();
        });
    }

    public static void onDbQueryStart(String sql, String dbHost) {
        safeRun(() -> {
            Map<String, Object> extra = new HashMap<>();
            extra.put("sql", truncate(sql));
            emit(TraceEventType.DB_QUERY_START, TraceCategory.DB, dbHost != null ? dbHost : "unknown-db", null, true, extra);
        });
    }

    public static void onDbQueryEnd(String sql, long durationMs, String dbHost) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("sql", truncate(sql));
            TcpSender.send(createChildEvent(txId, TraceEventType.DB_QUERY_END, TraceCategory.DB, dbHost, durationMs, true, extra));
        });
    }

    public static void onDbQueryError(Throwable t, String sql, long durationMs, String dbHost) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sql", truncate(sql));
            TcpSender.send(createChildEvent(txId, TraceEventType.DB_QUERY_END, TraceCategory.DB, dbHost, durationMs, false, extra));
        });
    }

    public static void onFileRead(String path, long sizeBytes, long durationMs, boolean success) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null || sizeBytes < AgentConfig.getMinSizeBytes()) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(createChildEvent(txId, TraceEventType.FILE_READ, TraceCategory.IO, path, durationMs, success, extra));
        });
    }

    public static void onFileWrite(String path, long sizeBytes, long durationMs, boolean success) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null || sizeBytes < AgentConfig.getMinSizeBytes()) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(createChildEvent(txId, TraceEventType.FILE_WRITE, TraceCategory.IO, path, durationMs, success, extra));
        });
    }

    public static void onCacheGet(String key, boolean hit) {
        safeRun(() -> emit(hit ? TraceEventType.CACHE_HIT : TraceEventType.CACHE_MISS, TraceCategory.CACHE, key, null, true, null));
    }

    public static void onCacheSet(String key) {
        safeRun(() -> emit(TraceEventType.CACHE_SET, TraceCategory.CACHE, key, null, true, null));
    }

    public static void onCacheDel(String key) {
        safeRun(() -> emit(TraceEventType.CACHE_DEL, TraceCategory.CACHE, key, null, true, null));
    }

    public static void attachCacheGetListener(Object future, String key) {
        safeRun(() -> ((java.util.concurrent.CompletionStage<?>) future).whenComplete((v, t) -> { if (t == null) onCacheGet(key, v != null); }));
    }

    public static void emitCustomEvent(TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra) {
        emit(type, category, target, durationMs, success, extra);
    }

    // -----------------------------------------------------------------------
    // Async / Thread Support
    // -----------------------------------------------------------------------

    public static String onAsyncStart(String taskName) {
        String txId = TxIdHolder.get();
        if (txId == null) return null;
        String spanId = generateSpanId();
        TcpSender.send(buildEvent(txId, TraceEventType.ASYNC_START, TraceCategory.ASYNC, 
                taskName, null, true, null, spanId, SpanIdHolder.get()));
        SpanIdHolder.set(spanId);
        return spanId;
    }

    public static void onAsyncEnd(String taskName, String spanId, long durationMs) {
        String txId = TxIdHolder.get();
        if (txId == null || spanId == null) return;
        TcpSender.send(createRootEvent(txId, TraceEventType.ASYNC_END, TraceCategory.ASYNC, taskName, durationMs, true, null, spanId));
    }

    public static Object wrapWebClientExchange(Object mono, String method, String uri) {
        try {
            String txId = TxIdHolder.get();
            if (txId == null) return mono;
            final String capturedTxId = txId;
            final String capturedSpanId = SpanIdHolder.get();
            long startTime = System.currentTimeMillis();

            java.util.function.Consumer<Object> successConsumer = response -> {
                long durationMs = System.currentTimeMillis() - startTime;
                int statusCode = extractStatusCode(response);
                emitHttpOutWithSpan(capturedTxId, capturedSpanId, method, uri, statusCode, durationMs, null);
            };
            java.util.function.Consumer<Throwable> errorConsumer = err -> {
                long durationMs = System.currentTimeMillis() - startTime;
                emitHttpOutWithSpan(capturedTxId, capturedSpanId, method, uri, -1, durationMs, err);
            };

            java.lang.reflect.Method doOnSuccess = mono.getClass().getMethod("doOnSuccess", java.util.function.Consumer.class);
            Object m2 = doOnSuccess.invoke(mono, successConsumer);
            java.lang.reflect.Method doOnError = m2.getClass().getMethod("doOnError", java.util.function.Consumer.class);
            return doOnError.invoke(m2, errorConsumer);
        } catch (Throwable t) { return mono; }
    }

    private static int extractStatusCode(Object response) {
        try {
            ClassLoader cl = response.getClass().getClassLoader();
            java.lang.reflect.Method scm = HTTP_STATUS_CODE_METHOD_CACHE.computeIfAbsent(cl, l -> {
                try { return Class.forName("org.springframework.web.reactive.function.client.ClientResponse", false, l).getMethod("statusCode"); }
                catch (Throwable t) { return null; }
            });
            Object scObj = scm.invoke(response);
            java.lang.reflect.Method vm = HTTP_STATUS_VALUE_METHOD_CACHE.computeIfAbsent(cl, l -> {
                try { return Class.forName("org.springframework.http.HttpStatusCode", false, l).getMethod("value"); }
                catch (Throwable t) { return null; }
            });
            return (int) vm.invoke(scObj);
        } catch (Throwable t) { return -1; }
    }

    private static void emitHttpOutWithSpan(String txId, String parentSpanId, String method, String uri, int statusCode, long durationMs, Throwable cause) {
        safeRun(() -> {
            boolean success = statusCode >= 200 && statusCode < 400 && cause == null;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method);
            extra.put("statusCode", statusCode);
            if (cause != null) extra.put("errorType", cause.getClass().getSimpleName());
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, uri, durationMs, success, extra, generateSpanId(), parentSpanId));
        });
    }

    private static void emit(TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId != null) TcpSender.send(createChildEvent(txId, type, category, target, durationMs, success, extra));
        });
    }

    private static TraceEvent createRootEvent(String txId, TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra, String spanId) {
        return buildEvent(txId, type, category, target, durationMs, success, extra, spanId, null);
    }

    private static TraceEvent createChildEvent(String txId, TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra) {
        return buildEvent(txId, type, category, target, durationMs, success, extra, generateSpanId(), SpanIdHolder.get());
    }

    private static TraceEvent buildEvent(String txId, TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra, String spanId, String parentSpanId) {
        return new TraceEvent(AgentConfig.getServerName() + "-" + EVENT_SEQ.incrementAndGet(), txId, spanId, parentSpanId, type, category, AgentConfig.getServerName(), target, durationMs, success, System.currentTimeMillis(), extra != null ? extra : new HashMap<>());
    }

    private static String generateSpanId() { return System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8); }

    public static String safeKeyToString(Object key) {
        if (key == null) return null;
        if (key instanceof byte[]) {
            byte[] bytes = (byte[]) key;
            try { return new String(bytes, java.nio.charset.StandardCharsets.UTF_8); }
            catch (Exception e) { return "[bytes:" + bytes.length + "]"; }
        }
        return String.valueOf(key);
    }

    private static void safeRun(Runnable r) { try { r.run(); } catch (Throwable t) { AgentLogger.error("Runtime error", t); } }

    private static String truncate(String s) { return (s == null || s.length() <= 1000) ? s : s.substring(0, 1000) + "..."; }

    private static String truncateMessage(String s) { return (s == null || s.length() <= 200) ? s : s.substring(0, 200) + "..."; }

    // -----------------------------------------------------------------------
    // WebFlux (Reactor) support
    // -----------------------------------------------------------------------

    public static void onWebFluxHandleStart(Object exchange) {
        safeRun(() -> {
            ClassLoader cl = exchange.getClass().getClassLoader();
            Object request = resolveAndInvoke(WF_GET_REQUEST_CACHE, cl, "org.springframework.web.server.ServerWebExchange", "getRequest", exchange);
            if (request == null) return;

            Object httpMethod = resolveAndInvoke(WF_REQ_METHOD_CACHE, cl, "org.springframework.http.server.reactive.ServerHttpRequest", "getMethod", request);
            String method = "UNKNOWN";
            try { method = String.valueOf(httpMethod.getClass().getMethod("name").invoke(httpMethod)); } catch (Throwable ignored) {}

            Object uriObj = resolveAndInvoke(WF_REQ_URI_CACHE, cl, "org.springframework.http.server.reactive.ServerHttpRequest", "getURI", request);
            String path = "/";
            try { path = String.valueOf(uriObj.getClass().getMethod("getPath").invoke(uriObj)); } catch (Throwable ignored) {}

            Object headers = resolveAndInvoke(WF_REQ_HEADERS_CACHE, cl, "org.springframework.http.server.reactive.ServerHttpRequest", "getHeaders", request);
            String inTxId = null, inSpanId = null;
            if (headers != null) {
                try {
                    java.lang.reflect.Method getFirst = headers.getClass().getMethod("getFirst", String.class);
                    inTxId = (String) getFirst.invoke(headers, AgentConfig.getHeaderKey());
                    inSpanId = (String) getFirst.invoke(headers, "X-Span-Id");
                } catch (Throwable ignored) {}
            }
            onHttpInStart(method, path, inTxId, inSpanId, false);
        });
    }

    public static Object wrapWebFluxHandle(Object mono, Object exchange, long startTimeMs) {
        try {
            String txId = TxIdHolder.get();
            if (txId == null) return mono;
            final String capturedTxId = txId;
            final String capturedSpanId = SpanIdHolder.get();
            ClassLoader cl = exchange.getClass().getClassLoader();

            java.util.function.Consumer<Object> successConsumer = unused -> {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                int status = extractWebFluxResponseStatus(exchange, cl);
                emitWebFluxEnd(capturedTxId, capturedSpanId, exchange, cl, durationMs, status, null);
            };
            java.util.function.Consumer<Throwable> errorConsumer = t -> {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                emitWebFluxEnd(capturedTxId, capturedSpanId, exchange, cl, durationMs, -1, t);
            };

            Object monoWithCtx = injectReactorContext(mono, capturedTxId, capturedSpanId, cl);
            java.lang.reflect.Method doOnSuccess = monoWithCtx.getClass().getMethod("doOnSuccess", java.util.function.Consumer.class);
            Object m2 = doOnSuccess.invoke(monoWithCtx, successConsumer);
            java.lang.reflect.Method doOnError = m2.getClass().getMethod("doOnError", java.util.function.Consumer.class);
            return doOnError.invoke(m2, errorConsumer);
        } catch (Throwable t) { return mono; }
    }

    private static void emitWebFluxEnd(String txId, String spanId, Object exchange, ClassLoader cl, long durationMs, int status, Throwable t) {
        safeRun(() -> {
            String[] mp = extractWebFluxMethodPath(exchange, cl);
            boolean success = status >= 200 && status < 400 && t == null;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", status);
            if (t != null) extra.put("errorType", t.getClass().getSimpleName());
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP, mp[0] + " " + mp[mp.length > 1 ? 1 : 0], durationMs, success, extra, spanId, null));
            TxIdHolder.clear();
            SpanIdHolder.clear();
        });
    }

    public static void onWebFluxHandleSyncError() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    private static Object resolveAndInvoke(java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method> cache, ClassLoader cl, String iface, String method, Object target) {
        try {
            java.lang.reflect.Method m = cache.computeIfAbsent(cl, l -> { try { return Class.forName(iface, false, l).getMethod(method); } catch (Throwable t) { return null; } });
            return m != null ? m.invoke(target) : null;
        } catch (Throwable t) { return null; }
    }

    private static String[] extractWebFluxMethodPath(Object exchange, ClassLoader cl) {
        try {
            Object req = resolveAndInvoke(WF_GET_REQUEST_CACHE, cl, "org.springframework.web.server.ServerWebExchange", "getRequest", exchange);
            Object hm = resolveAndInvoke(WF_REQ_METHOD_CACHE, cl, "org.springframework.http.server.reactive.ServerHttpRequest", "getMethod", req);
            String m = String.valueOf(hm.getClass().getMethod("name").invoke(hm));
            Object uri = resolveAndInvoke(WF_REQ_URI_CACHE, cl, "org.springframework.http.server.reactive.ServerHttpRequest", "getURI", req);
            String p = String.valueOf(uri.getClass().getMethod("getPath").invoke(uri));
            return new String[]{m, p};
        } catch (Throwable t) { return new String[]{"UNKNOWN", "/"}; }
    }

    private static int extractWebFluxResponseStatus(Object exchange, ClassLoader cl) {
        try {
            Object resp = resolveAndInvoke(WF_GET_RESPONSE_CACHE, cl, "org.springframework.web.server.ServerWebExchange", "getResponse", exchange);
            Object sc = resolveAndInvoke(WF_RESP_STATUS_CACHE, cl, "org.springframework.http.server.reactive.ServerHttpResponse", "getStatusCode", resp);
            java.lang.reflect.Method vm = HTTP_STATUS_VALUE_METHOD_CACHE.computeIfAbsent(cl, l -> { try { return Class.forName("org.springframework.http.HttpStatusCode", false, l).getMethod("value"); } catch (Throwable t) { return null; } });
            return (int) vm.invoke(sc);
        } catch (Throwable t) { return -1; }
    }

    private static Object injectReactorContext(Object mono, String txId, String spanId, ClassLoader cl) {
        try {
            Class<?> ctxClass = Class.forName("reactor.util.context.Context", false, cl);
            java.lang.reflect.Method of = ctxClass.getMethod("of", Object.class, Object.class, Object.class, Object.class);
            Object ctx = of.invoke(null, ReactorContextHolder.TX_ID_KEY, txId, ReactorContextHolder.SPAN_ID_KEY, spanId != null ? spanId : "");
            return mono.getClass().getMethod("contextWrite", Class.forName("reactor.util.context.ContextView", false, cl)).invoke(mono, ctx);
        } catch (Throwable t) { return mono; }
    }
}
