package org.example.agent.core.handler;

import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.ReactorContextHolder;
import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdGenerator;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class HttpEventHandler {

    private HttpEventHandler() {}

    // ── HTTP / WebFlux reflection caches (ClassValue => classloader-safe) ──
    private static final MethodCache HTTP_STATUS_CODE_METHOD_CACHE = new MethodCache("statusCode");
    private static final MethodCache HTTP_STATUS_VALUE_METHOD_CACHE = new MethodCache("value");
    private static final MethodCache WF_GET_REQUEST_CACHE = new MethodCache("getRequest");
    private static final MethodCache WF_GET_RESPONSE_CACHE = new MethodCache("getResponse");
    private static final MethodCache WF_REQ_METHOD_CACHE = new MethodCache("getMethod");
    private static final MethodCache WF_REQ_URI_CACHE = new MethodCache("getURI");
    private static final MethodCache WF_REQ_HEADERS_CACHE = new MethodCache("getHeaders");
    private static final MethodCache WF_RESP_STATUS_CACHE = new MethodCache("getStatusCode");

    // ── HTTP Inbound ──────────────────────────────────────────────────────

    public static void onInStart(Object request, String method, String path,
                          String incomingTxId, String incomingSpanId, boolean forceTrace) {
        TraceRuntime.safeRun(() -> {
            long now = System.currentTimeMillis();
            if (request != null) {
                TraceRuntime.invokeSetAttribute(request, TraceRuntime.TRACE_MARKER, Boolean.TRUE);
                TraceRuntime.invokeSetAttribute(request, "__TRACE_START_TIME__", now);
                TraceRuntime.invokeSetAttribute(request, "__TRACE_METHOD__", method);
                TraceRuntime.invokeSetAttribute(request, "__TRACE_PATH__", path);
                TraceRuntime.invokeSetAttribute(request, "__TRACE_FINISHED__", Boolean.FALSE);
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
                String spanId = TraceRuntime.generateSpanId();
                SpanIdHolder.set(spanId);

                if (request != null) {
                    TraceRuntime.invokeSetAttribute(request, TraceRuntime.ATTR_TX_ID, txId);
                    TraceRuntime.invokeSetAttribute(request, TraceRuntime.ATTR_SPAN_ID, spanId);
                }

                AgentLogger.debug("[RUNTIME] Transaction Started: " + method + " " + path + " (txId=" + txId + ")");
                AgentLogger.debug("[TRACE][HTTP][HTTP_IN_START] txId=" + txId
                    + " method=" + method + " path=" + path
                    + " incomingTxId=" + incomingTxId + " incomingSpanId=" + incomingSpanId);
                TcpSender.send(TraceRuntime.buildEvent(txId, TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
                        method + " " + path, null, true, null, spanId, incomingSpanId));
            }
        });
    }

    public static void onInEnd(String method, String path, int statusCode, long durationMs) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            String spanId = SpanIdHolder.get();
            boolean success = statusCode >= 200 && statusCode < 400;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", statusCode);
            AgentLogger.debug("[TRACE][HTTP][HTTP_IN_END] txId=" + txId
                + " method=" + method + " path=" + path + " statusCode=" + statusCode
                + " durationMs=" + durationMs + " success=" + success);
            TcpSender.send(TraceRuntime.buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP,
                    method + " " + path, durationMs, success, extra, spanId, null));
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    public static void onInEndAsync(String txId, String spanId, String method,
                             String path, long startTime, Object request,
                             String terminalType, Object asyncEvent) {
        TraceRuntime.safeRun(() -> {
            if (request != null && Boolean.TRUE.equals(TraceRuntime.invokeGetAttribute(request, "__TRACE_FINISHED__"))) return;
            if (request != null) TraceRuntime.invokeSetAttribute(request, "__TRACE_FINISHED__", Boolean.TRUE);

            long durationMs = System.currentTimeMillis() - startTime;
            AgentLogger.debug("[RUNTIME] Transaction Completed (Async): txId=" + txId + " (" + durationMs + "ms)");
            Map<String, Object> extra = new LinkedHashMap<>();
            int statusCode = resolveAsyncStatusCode(terminalType, asyncEvent);
            boolean success = "onComplete".equals(terminalType) && statusCode >= 200 && statusCode < 400;
            extra.put("statusCode", statusCode);
            extra.put("async", true);
            extra.put("asyncTerminal", terminalType);
            if ("onError".equals(terminalType)) {
                Object throwable = asyncEvent != null ? TraceRuntime.invokeMethodSimple(asyncEvent, "getThrowable") : null;
                if (throwable instanceof Throwable) {
                    extra.put("errorType", ((Throwable) throwable).getClass().getSimpleName());
                    extra.put("errorMessage", ((Throwable) throwable).getMessage() != null ? ((Throwable) throwable).getMessage() : "");
                } else {
                    extra.put("errorType", "AsyncError");
                    extra.put("errorMessage", "");
                }
            }
            if ("onTimeout".equals(terminalType)) {
                extra.put("timeout", true);
            }
            AgentLogger.debug("[TRACE][HTTP][HTTP_IN_END] txId=" + txId
                + " method=" + method + " path=" + path + " statusCode=" + statusCode
                + " durationMs=" + durationMs + " success=" + success
                + " async=true terminal=" + terminalType
                + (extra.containsKey("errorType") ? " errorType=" + extra.get("errorType") : "")
                + (extra.containsKey("errorMessage") ? " errorMessage=" + extra.get("errorMessage") : ""));
            TcpSender.send(TraceRuntime.buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP,
                    method + " " + path, durationMs, success, extra, spanId, null));
        });
    }

    private static int resolveAsyncStatusCode(String terminalType, Object asyncEvent) {
        Integer responseStatus = tryExtractAsyncResponseStatus(asyncEvent);
        if (responseStatus != null) return responseStatus;
        if ("onTimeout".equals(terminalType)) return 504;
        if ("onError".equals(terminalType)) return 500;
        return 200;
    }

    private static Integer tryExtractAsyncResponseStatus(Object asyncEvent) {
        if (asyncEvent == null) return null;
        try {
            Object suppliedResponse = TraceRuntime.invokeMethodSimple(asyncEvent, "getSuppliedResponse");
            if (suppliedResponse == null) return null;
            Method statusMethod = TraceRuntime.findMethod(suppliedResponse.getClass(), "getStatus");
            if (statusMethod == null) return null;
            Object status = statusMethod.invoke(suppliedResponse);
            if (status instanceof Number) return ((Number) status).intValue();
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void onInError(Throwable t, String method, String path, long durationMs) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            String spanId = SpanIdHolder.get();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? t.getMessage() : "");
            AgentLogger.debug("[TRACE][HTTP][HTTP_IN_END] txId=" + txId
                + " method=" + method + " path=" + path + " durationMs=" + durationMs
                + " success=false errorType=" + extra.get("errorType")
                + " errorMessage=" + extra.get("errorMessage"));
            TcpSender.send(TraceRuntime.buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP,
                    method + " " + path, durationMs, false, extra, spanId, null));
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    public static void registerFromRequest(Object request) {
        if (request == null) return;
        TraceRuntime.safeRun(() -> {
            Object method = TraceRuntime.invokeGetAttribute(request, "__TRACE_METHOD__");
            Object path   = TraceRuntime.invokeGetAttribute(request, "__TRACE_PATH__");
            Object startTime = TraceRuntime.invokeGetAttribute(request, "__TRACE_START_TIME__");
            if (method != null && path != null && startTime instanceof Long) {
                register(request, (String) method, (String) path, (Long) startTime);
            }
        });
    }

    public static void register(Object request, String method, String path, long startTime) {
        TraceRuntime.safeRun(() -> {
            if (request == null) return;
            if (Boolean.TRUE.equals(TraceRuntime.invokeGetAttribute(request, "__TRACE_ASYNC_REGISTERED__"))) return;

            String txId   = (String) TraceRuntime.invokeGetAttribute(request, TraceRuntime.ATTR_TX_ID);
            String spanId = (String) TraceRuntime.invokeGetAttribute(request, TraceRuntime.ATTR_SPAN_ID);

            if (txId == null) txId = TxIdHolder.get();
            if (spanId == null) spanId = SpanIdHolder.get();
            if (txId == null) return;

            try {
                Object asyncCtx = TraceRuntime.invokeMethodSimple(request, "getAsyncContext");
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

                Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{finalListenerClass},
                        new java.lang.reflect.InvocationHandler() {
                            private final java.util.concurrent.atomic.AtomicBoolean completed =
                                    new java.util.concurrent.atomic.AtomicBoolean(false);
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                                String name = m.getName();
                                if (("onComplete".equals(name) || "onError".equals(name) || "onTimeout".equals(name))
                                        && completed.compareAndSet(false, true)) {
                                    Object asyncEvent = (args != null && args.length > 0) ? args[0] : null;
                                    onInEndAsync(finalTxId, finalSpanId, method, path, startTime, request, name, asyncEvent);
                                }
                                return null;
                            }
                        });

                Method addListener = TraceRuntime.findMethod(asyncCtx.getClass(), "addListener", finalListenerClass);
                if (addListener != null) {
                    addListener.invoke(asyncCtx, proxy);
                    TraceRuntime.invokeSetAttribute(request, "__TRACE_ASYNC_REGISTERED__", Boolean.TRUE);
                    AgentLogger.debug("[RUNTIME] Registered AsyncListener for txId=" + finalTxId);
                }
            } catch (Throwable err) {
                AgentLogger.error("Failed to register AsyncListener", err);
            }
        });
    }

    // ── HTTP Outbound ──────────────────────────────────────────────────────

    public static void onOut(String method, String uri, int statusCode, long durationMs) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("statusCode", statusCode); extra.put("method", method);
            AgentLogger.debug("[TRACE][HTTP][HTTP_OUT] txId=" + txId
                + " method=" + method + " uri=" + uri + " statusCode=" + statusCode
                + " durationMs=" + durationMs + " success=" + (statusCode >= 200 && statusCode < 400));
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.HTTP_OUT,
                    TraceCategory.HTTP, uri, durationMs, statusCode >= 200 && statusCode < 400, extra));
        });
    }

    public static void onOutError(Throwable t, String method, String url, long durationMs) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get(); if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? t.getMessage() : "");
            AgentLogger.debug("[TRACE][HTTP][HTTP_OUT] txId=" + txId
                + " method=" + method + " uri=" + url + " durationMs=" + durationMs
                + " success=false errorType=" + extra.get("errorType")
                + " errorMessage=" + extra.get("errorMessage"));
            TcpSender.send(TraceRuntime.createChildEvent(txId, TraceEventType.HTTP_OUT,
                    TraceCategory.HTTP, url, durationMs, false, extra));
        });
    }

    // ── WebClient / WebFlux ───────────────────────────────────────────────

    public static Object wrapWebClient(Object mono, String method, String uri) {
        try {
            String txId = TxIdHolder.get(); if (txId == null) return mono;
            final String capturedTxId = txId;
            final String capturedSpanId = SpanIdHolder.get();
            long startTime = System.currentTimeMillis();

            Consumer<Object> successConsumer = response -> {
                long durationMs = System.currentTimeMillis() - startTime;
                int statusCode = extractStatusCode(response);
                emitHttpOutWithSpan(capturedTxId, capturedSpanId, method, uri, statusCode, durationMs, null);
            };
            Consumer<Throwable> errorConsumer = err -> {
                long durationMs = System.currentTimeMillis() - startTime;
                emitHttpOutWithSpan(capturedTxId, capturedSpanId, method, uri, -1, durationMs, err);
            };

            Method doOnSuccess = mono.getClass().getMethod("doOnSuccess", Consumer.class);
            Object m2 = doOnSuccess.invoke(mono, successConsumer);
            Method doOnError = m2.getClass().getMethod("doOnError", Consumer.class);
            return doOnError.invoke(m2, errorConsumer);
        } catch (Throwable t) { return mono; }
    }

    public static void onWfStart(Object exchange) {
        TraceRuntime.safeRun(() -> {
            Object request = resolveAndInvoke(WF_GET_REQUEST_CACHE, exchange);
            if (request == null) return;
            Object httpMethod = resolveAndInvoke(WF_REQ_METHOD_CACHE, request);
            String method = "UNKNOWN";
            try { method = String.valueOf(httpMethod.getClass().getMethod("name").invoke(httpMethod)); } catch (Throwable ignored) {}
            Object uriObj = resolveAndInvoke(WF_REQ_URI_CACHE, request);
            String path = "/";
            try { path = String.valueOf(uriObj.getClass().getMethod("getPath").invoke(uriObj)); } catch (Throwable ignored) {}
            Object headers = resolveAndInvoke(WF_REQ_HEADERS_CACHE, request);
            String inTxId = null, inSpanId = null;
            if (headers != null) {
                try {
                    Method getFirst = headers.getClass().getMethod("getFirst", String.class);
                    inTxId  = (String) getFirst.invoke(headers, AgentConfig.getHeaderKey());
                    inSpanId = (String) getFirst.invoke(headers, "X-Span-Id");
                } catch (Throwable ignored) {}
            }
            onInStart(request, method, path, inTxId, inSpanId, false);
        });
    }

    public static Object wrapWfHandle(Object mono, Object exchange, long startTimeMs) {
        try {
            String txId = TxIdHolder.get(); if (txId == null) return mono;
            final String capturedTxId = txId;
            final String capturedSpanId = SpanIdHolder.get();
            ClassLoader cl = exchange.getClass().getClassLoader();
            Consumer<Object> successConsumer = unused -> {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                int status = extractWebFluxResponseStatus(exchange, cl);
                emitWebFluxEnd(capturedTxId, capturedSpanId, exchange, cl, durationMs, status, null);
            };
            Consumer<Throwable> errorConsumer = t -> {
                long durationMs = System.currentTimeMillis() - startTimeMs;
                emitWebFluxEnd(capturedTxId, capturedSpanId, exchange, cl, durationMs, -1, t);
            };
            Object monoWithCtx = injectReactorContext(mono, capturedTxId, capturedSpanId, cl);
            Method doOnSuccess = monoWithCtx.getClass().getMethod("doOnSuccess", Consumer.class);
            Object m2 = doOnSuccess.invoke(monoWithCtx, successConsumer);
            Method doOnError = m2.getClass().getMethod("doOnError", Consumer.class);
            return doOnError.invoke(m2, errorConsumer);
        } catch (Throwable t) { return mono; }
    }

    public static void onWfSyncError() { TxIdHolder.clear(); SpanIdHolder.clear(); }

    // ── Private helpers ───────────────────────────────────────────────────

    private static void emitWebFluxEnd(String txId, String spanId, Object exchange,
                                       ClassLoader cl, long durationMs, int status, Throwable t) {
        TraceRuntime.safeRun(() -> {
            String[] mp = extractWebFluxMethodPath(exchange, cl);
            boolean success = status >= 200 && status < 400 && t == null;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", status);
            if (t != null) {
                extra.put("errorType", t.getClass().getSimpleName());
                extra.put("errorMessage", t.getMessage() != null ? t.getMessage() : "");
            }
            TcpSender.send(TraceRuntime.buildEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP,
                    mp[0] + " " + mp[mp.length > 1 ? 1 : 0], durationMs, success, extra, spanId, null));
            TxIdHolder.clear(); SpanIdHolder.clear();
        });
    }

    private static int extractStatusCode(Object response) {
        try {
            Method scm = HTTP_STATUS_CODE_METHOD_CACHE.get(response.getClass());
            if (scm == null) return -1;
            Object scObj = scm.invoke(response);
            if (scObj == null) return -1;
            Method vm = HTTP_STATUS_VALUE_METHOD_CACHE.get(scObj.getClass());
            if (vm == null) return -1;
            return (int) vm.invoke(scObj);
        } catch (Throwable t) { return -1; }
    }

    private static void emitHttpOutWithSpan(String txId, String parentSpanId, String method,
                                            String uri, int statusCode, long durationMs, Throwable cause) {
        TraceRuntime.safeRun(() -> {
            boolean success = statusCode >= 200 && statusCode < 400 && cause == null;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method); extra.put("statusCode", statusCode);
            if (cause != null) {
                extra.put("errorType", cause.getClass().getSimpleName());
                extra.put("errorMessage", cause.getMessage() != null ? cause.getMessage() : "");
            }
            TcpSender.send(TraceRuntime.buildEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP,
                    uri, durationMs, success, extra, TraceRuntime.generateSpanId(), parentSpanId));
        });
    }

    private static int extractWebFluxResponseStatus(Object exchange, ClassLoader cl) {
        try {
            Object resp = resolveAndInvoke(WF_GET_RESPONSE_CACHE, exchange);
            Object sc = resolveAndInvoke(WF_RESP_STATUS_CACHE, resp);
            if (sc == null) return -1;
            Method vm = HTTP_STATUS_VALUE_METHOD_CACHE.get(sc.getClass());
            if (vm == null) return -1;
            return (int) vm.invoke(sc);
        } catch (Throwable t) { return -1; }
    }

    private static Object injectReactorContext(Object mono, String txId, String spanId, ClassLoader cl) {
        try {
            Class<?> ctxClass = Class.forName("reactor.util.context.Context", false, cl);
            Method of = ctxClass.getMethod("of", Object.class, Object.class, Object.class, Object.class);
            Object ctx = of.invoke(null, ReactorContextHolder.TX_ID_KEY, txId,
                    ReactorContextHolder.SPAN_ID_KEY, spanId != null ? spanId : "");
            return mono.getClass().getMethod("contextWrite",
                    Class.forName("reactor.util.context.ContextView", false, cl)).invoke(mono, ctx);
        } catch (Throwable t) { return mono; }
    }

    private static String[] extractWebFluxMethodPath(Object exchange, ClassLoader cl) {
        try {
            Object req = resolveAndInvoke(WF_GET_REQUEST_CACHE, exchange);
            Object hm = resolveAndInvoke(WF_REQ_METHOD_CACHE, req);
            String m = String.valueOf(hm.getClass().getMethod("name").invoke(hm));
            Object uri = resolveAndInvoke(WF_REQ_URI_CACHE, req);
            String p = String.valueOf(uri.getClass().getMethod("getPath").invoke(uri));
            return new String[]{m, p};
        } catch (Throwable t) { return new String[]{"UNKNOWN", "/"}; }
    }

    // Backward-compatible overload kept for private-reflection tests.
    @SuppressWarnings("unused")
    private static Object resolveAndInvoke(java.util.concurrent.ConcurrentHashMap<ClassLoader, Method> cache,
                                           ClassLoader cl, String iface, String method, Object target) {
        try {
            if (target == null) return null;
            Method m = cache.computeIfAbsent(cl, l -> {
                try { return Class.forName(iface, false, l).getMethod(method); }
                catch (Throwable t) { return null; }
            });
            return m != null ? m.invoke(target) : null;
        } catch (Throwable t) { return null; }
    }

    private static Object resolveAndInvoke(MethodCache cache, Object target) {
        try {
            if (target == null) return null;
            Method m = cache.get(target.getClass());
            return m != null ? m.invoke(target) : null;
        } catch (Throwable t) { return null; }
    }

    private static final class MethodCache {
        private final ClassValue<Method> cache;

        private MethodCache(String methodName) {
            this.cache = new ClassValue<Method>() {
                @Override
                protected Method computeValue(Class<?> type) {
                    return TraceRuntime.findMethod(type, methodName);
                }
            };
        }

        private Method get(Class<?> type) {
            return cache.get(type);
        }
    }
}
