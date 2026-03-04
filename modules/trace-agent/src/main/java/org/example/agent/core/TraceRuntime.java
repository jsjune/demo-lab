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
    private static final String TRACE_MARKER = "__TRACE_TRACKING__";
    private static final String ATTR_TX_ID = "__TRACE_TX_ID__";
    private static final String ATTR_SPAN_ID = "__TRACE_SPAN_ID__";

    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        GET_ATTR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        SET_ATTR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    
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

    private static String generateSpanId() {
        return System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    // -----------------------------------------------------------------------
    // Core Logic: Lifecycle & Duplication Prevention
    // -----------------------------------------------------------------------

    public static boolean isSecondaryDispatch(Object request) {
        if (request == null) return false;
        try {
            if (Boolean.TRUE.equals(invokeGetAttribute(request, TRACE_MARKER))) return true;
            Object type = invokeMethodSimple(request, "getDispatcherType");
            if (type != null) {
                String typeName = type.toString();
                return "ASYNC".equals(typeName) || "ERROR".equals(typeName);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void restoreContext(Object request) {
        safeRun(() -> {
            String txId = (String) invokeGetAttribute(request, ATTR_TX_ID);
            String spanId = (String) invokeGetAttribute(request, ATTR_SPAN_ID);
            if (txId != null) {
                TxIdHolder.set(txId);
                SpanIdHolder.set(spanId);
                AgentLogger.debug("[RUNTIME] Context Restored (Async Resume): txId=" + txId);
            }
        });
    }

    // -----------------------------------------------------------------------
    // HTTP Inbound (MVC / WebFlux)
    // -----------------------------------------------------------------------

    public static void onHttpInStart(Object request, String method, String path, String incomingTxId, String incomingSpanId, boolean forceTrace) {
        safeRun(() -> {
            long now = System.currentTimeMillis();
            if (request != null) {
                invokeSetAttribute(request, TRACE_MARKER, Boolean.TRUE);
                invokeSetAttribute(request, "__TRACE_START_TIME__", now);
                invokeSetAttribute(request, "__TRACE_METHOD__", method);
                invokeSetAttribute(request, "__TRACE_PATH__", path);
                invokeSetAttribute(request, "__TRACE_FINISHED__", Boolean.FALSE);
            }

            if (incomingTxId != null && !incomingTxId.isEmpty()) {
                TxIdHolder.set(incomingTxId);
                AgentLogger.debug("[RUNTIME] Adopted incoming txId: " + incomingTxId);
            } else if (TxIdHolder.get() == null) {
                if (!forceTrace && !TxIdGenerator.shouldSample()) return;
                TxIdHolder.set(TxIdGenerator.generate());
                AgentLogger.debug("[RUNTIME] Generated new txId: " + TxIdHolder.get());
            }
            
            String txId = TxIdHolder.get();
            if (txId != null) {
                String spanId = generateSpanId();
                SpanIdHolder.set(spanId);
                
                if (request != null) {
                    invokeSetAttribute(request, ATTR_TX_ID, txId);
                    invokeSetAttribute(request, ATTR_SPAN_ID, spanId);
                }

                AgentLogger.debug("[RUNTIME] Transaction Started: " + method + " " + path + " (txId=" + txId + ")");
                TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
                        method + " " + path, null, true, null, spanId, incomingSpanId));
            }
        });
    }

    public static void onHttpInEnd(String method, String path, int statusCode, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            String spanId = SpanIdHolder.get();
            boolean success = statusCode >= 200 && statusCode < 400;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", statusCode);
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP, method + " " + path, durationMs, success, extra, spanId, null));
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    public static void onHttpInEndAsync(String txId, String spanId, String method, String path, long startTime, Object request) {
        safeRun(() -> {
            if (request != null && Boolean.TRUE.equals(invokeGetAttribute(request, "__TRACE_FINISHED__"))) return;
            if (request != null) invokeSetAttribute(request, "__TRACE_FINISHED__", Boolean.TRUE);

            long durationMs = System.currentTimeMillis() - startTime;
            AgentLogger.debug("[RUNTIME] Transaction Completed (Async): txId=" + txId + " (" + durationMs + "ms)");
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", 200); extra.put("async", true);
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP, method + " " + path, durationMs, true, extra, spanId, null));
        });
    }

    public static void onHttpInError(Throwable t, String method, String path, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            String spanId = SpanIdHolder.get();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP, method + " " + path, durationMs, false, extra, spanId, null));
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    public static void registerAsyncListenerFromRequest(Object request) {
        if (request == null) return;
        safeRun(() -> {
            Object method = invokeGetAttribute(request, "__TRACE_METHOD__");
            Object path = invokeGetAttribute(request, "__TRACE_PATH__");
            Object startTime = invokeGetAttribute(request, "__TRACE_START_TIME__");
            if (method != null && path != null && startTime instanceof Long) {
                registerAsyncListener(request, (String)method, (String)path, (Long)startTime);
            }
        });
    }

    public static void registerAsyncListener(Object request, String method, String path, long startTime) {
        safeRun(() -> {
            if (request == null) return;
            if (Boolean.TRUE.equals(invokeGetAttribute(request, "__TRACE_ASYNC_REGISTERED__"))) return;

            String txId = (String) invokeGetAttribute(request, ATTR_TX_ID);
            String spanId = (String) invokeGetAttribute(request, ATTR_SPAN_ID);
            
            if (txId == null) txId = TxIdHolder.get();
            if (spanId == null) spanId = SpanIdHolder.get();
            if (txId == null) return;

            try {
                Object asyncCtx = invokeMethodSimple(request, "getAsyncContext");
                if (asyncCtx == null) return;

                ClassLoader cl = request.getClass().getClassLoader();
                Class<?> listenerClass = null;
                try {
                    listenerClass = Class.forName("jakarta.servlet.AsyncListener", false, cl);
                } catch (ClassNotFoundException e) {
                    try {
                        listenerClass = Class.forName("javax.servlet.AsyncListener", false, cl);
                    } catch (ClassNotFoundException e2) {
                        return;
                    }
                }

                final String finalTxId = txId;
                final String finalSpanId = spanId;
                final Class<?> finalListenerClass = listenerClass;

                Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{finalListenerClass}, new java.lang.reflect.InvocationHandler() {
                    private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method1, Object[] args) throws Throwable {
                        String name = method1.getName();
                        if (("onComplete".equals(name) || "onError".equals(name) || "onTimeout".equals(name)) && completed.compareAndSet(false, true)) {
                            onHttpInEndAsync(finalTxId, finalSpanId, method, path, startTime, request);
                        }
                        return null;
                    }
                });

                java.lang.reflect.Method addListener = findMethod(asyncCtx.getClass(), "addListener", finalListenerClass);
                if (addListener != null) {
                    addListener.invoke(asyncCtx, proxy);
                    invokeSetAttribute(request, "__TRACE_ASYNC_REGISTERED__", Boolean.TRUE);
                    AgentLogger.debug("[RUNTIME] Registered AsyncListener for txId=" + finalTxId);
                }
            } catch (Throwable t) {
                AgentLogger.error("Failed to register AsyncListener", t);
            }
        });
    }

    // -----------------------------------------------------------------------
    // HTTP Outbound (RestTemplate / WebClient)
    // -----------------------------------------------------------------------

    public static void onHttpOut(String method, String uri, int statusCode, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("statusCode", statusCode); extra.put("method", method);
            TcpSender.send(createChildEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, uri, durationMs, statusCode >= 200 && statusCode < 400, extra));
        });
    }

    public static void onHttpOutError(Throwable t, String method, String url, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method); extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            TcpSender.send(createChildEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, url, durationMs, false, extra));
        });
    }

    // -----------------------------------------------------------------------
    // MQ (Kafka) Operations
    // -----------------------------------------------------------------------

    public static void onMqProduce(String brokerType, String topic, String key) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("brokerType", brokerType);
            TcpSender.send(createChildEvent(txId, TraceEventType.MQ_PRODUCE, TraceCategory.MQ, topic, null, true, extra));
        });
    }

    public static void onMqConsumeStart(String brokerType, String topic, String incomingTxId) {
        safeRun(() -> {
            if (incomingTxId != null && !incomingTxId.isEmpty()) TxIdHolder.set(incomingTxId);
            else if (TxIdHolder.get() == null) { if (!TxIdGenerator.shouldSample()) return; TxIdHolder.set(TxIdGenerator.generate()); }
            String txId = TxIdHolder.get(); String spanId = generateSpanId(); SpanIdHolder.set(spanId);
            TcpSender.send(createRootEvent(txId, TraceEventType.MQ_CONSUME_START, TraceCategory.MQ, topic, null, true, null, spanId));
        });
    }

    public static void onMqConsumeEnd(String brokerType, String topic, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            TcpSender.send(createRootEvent(txId, TraceEventType.MQ_CONSUME_END, TraceCategory.MQ, topic, durationMs, true, null, SpanIdHolder.get()));
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    public static void onMqConsumeError(Throwable t, String brokerType, String topic, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            TcpSender.send(createRootEvent(txId, TraceEventType.MQ_CONSUME_END, TraceCategory.MQ, topic, durationMs, false, null, SpanIdHolder.get()));
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    // -----------------------------------------------------------------------
    // DB (JDBC) Operations
    // -----------------------------------------------------------------------

    public static void onDbQueryStart(String sql, String dbHost) {
        safeRun(() -> {
            Map<String, Object> extra = new HashMap<>(); extra.put("sql", truncate(sql));
            emit(TraceEventType.DB_QUERY_START, TraceCategory.DB, dbHost != null ? dbHost : "unknown-db", null, true, extra);
        });
    }

    public static void onDbQueryEnd(String sql, long durationMs, String dbHost) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new HashMap<>(); extra.put("sql", truncate(sql));
            TcpSender.send(createChildEvent(txId, TraceEventType.DB_QUERY_END, TraceCategory.DB, dbHost, durationMs, true, extra));
        });
    }

    public static void onDbQueryError(Throwable t, String sql, long durationMs, String dbHost) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>(); extra.put("sql", truncate(sql));
            TcpSender.send(createChildEvent(txId, TraceEventType.DB_QUERY_END, TraceCategory.DB, dbHost, durationMs, false, extra));
        });
    }

    // -----------------------------------------------------------------------
    // Cache (Redis) Operations
    // -----------------------------------------------------------------------

    public static void onCacheGet(String key, boolean hit) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new HashMap<>(); extra.put("key", key);
            TcpSender.send(createChildEvent(txId, hit ? TraceEventType.CACHE_HIT : TraceEventType.CACHE_MISS, TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    public static void onCacheSet(String key) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new HashMap<>(); extra.put("key", key);
            TcpSender.send(createChildEvent(txId, TraceEventType.CACHE_SET, TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    public static void onCacheDel(String key) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new HashMap<>(); extra.put("key", key);
            TcpSender.send(createChildEvent(txId, TraceEventType.CACHE_DEL, TraceCategory.CACHE, "redis", null, true, extra));
        });
    }

    // -----------------------------------------------------------------------
    // File I/O Operations
    // -----------------------------------------------------------------------

    public static void onFileRead(String path, long sizeBytes, long durationMs, boolean success) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(createChildEvent(
                txId,
                TraceEventType.FILE_READ,
                TraceCategory.IO,
                path != null ? path : "unknown-file",
                durationMs,
                success,
                extra
            ));
        });
    }

    public static void onFileWrite(String path, long sizeBytes, long durationMs, boolean success) {
        safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(createChildEvent(
                txId,
                TraceEventType.FILE_WRITE,
                TraceCategory.IO,
                path != null ? path : "unknown-file",
                durationMs,
                success,
                extra
            ));
        });
    }

    // -----------------------------------------------------------------------
    // Async / Thread Support
    // -----------------------------------------------------------------------

    public static String onAsyncStart(String taskName) {
        String txId = TxIdHolder.get();
        if (txId == null) return null;
        String spanId = generateSpanId();
        AgentLogger.debug("[RUNTIME] ASYNC START: " + taskName + " (txId=" + txId + ", spanId=" + spanId + ")");
        TcpSender.send(buildEvent(txId, TraceEventType.ASYNC_START, TraceCategory.ASYNC, taskName, null, true, null, spanId, SpanIdHolder.get()));
        SpanIdHolder.set(spanId);
        return spanId;
    }

    public static void onAsyncEnd(String taskName, String spanId, long durationMs) {
        String txId = TxIdHolder.get(); if (txId == null || spanId == null) return;
        TcpSender.send(createRootEvent(txId, TraceEventType.ASYNC_END, TraceCategory.ASYNC, taskName, durationMs, true, null, spanId));
    }

    // -----------------------------------------------------------------------
    // WebFlux / Reactor support
    // -----------------------------------------------------------------------

    public static Object wrapWebClientExchange(Object mono, String method, String uri) {
        try {
            String txId = TxIdHolder.get(); if (txId == null) return mono;
            final String capturedTxId = txId; final String capturedSpanId = SpanIdHolder.get();
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
            onHttpInStart(request, method, path, inTxId, inSpanId, false);
        });
    }

    public static Object wrapWebFluxHandle(Object mono, Object exchange, long startTimeMs) {
        try {
            String txId = TxIdHolder.get(); if (txId == null) return mono;
            final String capturedTxId = txId; final String capturedSpanId = SpanIdHolder.get();
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
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    public static void onWebFluxHandleSyncError() { TxIdHolder.clear(); SpanIdHolder.clear(); }

    private static int extractStatusCode(Object response) {
        try {
            ClassLoader cl = response.getClass().getClassLoader();
            java.lang.reflect.Method scm = HTTP_STATUS_CODE_METHOD_CACHE.computeIfAbsent(cl, l -> {
                try { return Class.forName("org.springframework.web.reactive.function.client.ClientResponse", false, l).getMethod("statusCode"); } catch (Throwable t) { return null; }
            });
            Object scObj = scm.invoke(response);
            java.lang.reflect.Method vm = HTTP_STATUS_VALUE_METHOD_CACHE.computeIfAbsent(cl, l -> {
                try { return Class.forName("org.springframework.http.HttpStatusCode", false, l).getMethod("value"); } catch (Throwable t) { return null; }
            });
            return (int) vm.invoke(scObj);
        } catch (Throwable t) { return -1; }
    }

    private static void emitHttpOutWithSpan(String txId, String parentSpanId, String method, String uri, int statusCode, long durationMs, Throwable cause) {
        safeRun(() -> {
            boolean success = statusCode >= 200 && statusCode < 400 && cause == null;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method); extra.put("statusCode", statusCode);
            if (cause != null) extra.put("errorType", cause.getClass().getSimpleName());
            TcpSender.send(buildEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, uri, durationMs, success, extra, generateSpanId(), parentSpanId));
        });
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

    // -----------------------------------------------------------------------
    // Reflection Helpers
    // -----------------------------------------------------------------------

    public static Object invokeGetAttribute(Object target, String name) {
        try {
            ClassLoader cl = target.getClass().getClassLoader();
            java.lang.reflect.Method m = GET_ATTR_CACHE.computeIfAbsent(cl, loader -> findMethod(target.getClass(), "getAttribute", String.class));
            return m != null ? m.invoke(target, name) : null;
        } catch (Throwable t) { return null; }
    }

    public static void invokeSetAttribute(Object target, String name, Object val) {
        try {
            ClassLoader cl = target.getClass().getClassLoader();
            java.lang.reflect.Method m = SET_ATTR_CACHE.computeIfAbsent(cl, loader -> findMethod(target.getClass(), "setAttribute", String.class, Object.class));
            if (m != null) m.invoke(target, name, val);
        } catch (Throwable ignored) {}
    }

    public static Object invokeMethodSimple(Object target, String name) {
        try {
            java.lang.reflect.Method m = findMethod(target.getClass(), name);
            return m != null ? m.invoke(target) : null;
        } catch (Throwable t) { return null; }
    }

    public static java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Method m = current.getDeclaredMethod(name, parameterTypes);
                m.setAccessible(true); return m;
            } catch (NoSuchMethodException e) { current = current.getSuperclass(); }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            try { return iface.getMethod(name, parameterTypes); } catch (NoSuchMethodException ignored) {}
        }
        return null;
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

    // -----------------------------------------------------------------------
    // Internal Event Factories
    // -----------------------------------------------------------------------

    private static TraceEvent createRootEvent(String txId, TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra, String spanId) {
        return buildEvent(txId, type, category, target, durationMs, success, extra, spanId, null);
    }

    private static TraceEvent createChildEvent(String txId, TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra) {
        return buildEvent(txId, type, category, target, durationMs, success, extra, generateSpanId(), SpanIdHolder.get());
    }

    private static TraceEvent buildEvent(String txId, TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra, String spanId, String parentSpanId) {
        return new TraceEvent(AgentConfig.getServerName() + "-" + EVENT_SEQ.incrementAndGet(), txId, spanId, parentSpanId, type, category, AgentConfig.getServerName(), target, durationMs, success, System.currentTimeMillis(), extra != null ? extra : new HashMap<>());
    }

    private static void emit(TraceEventType type, TraceCategory category, String target, Long durationMs, boolean success, Map<String, Object> extra) {
        safeRun(() -> { String txId = TxIdHolder.get(); if (txId != null) TcpSender.send(createChildEvent(txId, type, category, target, durationMs, success, extra)); });
    }

    public static String safeKeyToString(Object key) {
        if (key == null) return null;
        if (key instanceof byte[]) {
            byte[] bytes = (byte[]) key;
            try { return new String(bytes, java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { return "[bytes:" + bytes.length + "]"; }
        }
        return String.valueOf(key);
    }

    private static void safeRun(Runnable r) { try { r.run(); } catch (Throwable t) { AgentLogger.error("Runtime error", t); } }
    private static String truncate(String s) { return (s == null || s.length() <= 1000) ? s : s.substring(0, 1000) + "..."; }
}
