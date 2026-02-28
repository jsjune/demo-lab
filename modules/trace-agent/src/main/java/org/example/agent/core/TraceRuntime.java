package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global entry point for all instrumented code.
 * All public static onXxx() signatures are part of the bytecode ABI and must not change.
 *
 * <p>Safety guarantee: every method is wrapped in safeRun() so agent logic can never
 * propagate an exception to the target application.
 */
public class TraceRuntime {

    /**
     * Returns true when the HttpServletRequest is Spring Boot's internal error-dispatch
     * (i.e. an error forward or native ERROR dispatch — NOT a direct client request).
     *
     * <p>Two detection strategies, tried in order:
     * <ol>
     *   <li><b>DispatcherType.ERROR</b> — native container error dispatch (Tomcat / Jetty
     *       handle the error page themselves, used by Spring Boot 4 by default).
     *       The container sets DispatcherType to ERROR on the forwarded request.
     *   <li><b>Error attribute</b> — Spring Boot's {@code ErrorPageFilter} path: ErrorPageFilter
     *       catches the exception, sets {@code jakarta/javax.servlet.error.request_uri} on the
     *       request, then does a {@code RequestDispatcher.forward()} (DispatcherType=FORWARD).
     *       Attribute is checked via the public {@code jakarta/javax.servlet.ServletRequest}
     *       <em>interface</em> to avoid JPMS access errors on non-exported Tomcat internals.
     * </ol>
     *
     * <p>A direct client {@code GET /error} has neither ERROR DispatcherType nor the error
     * attribute, so it is correctly passed through and traced.
     *
     * <p>Called from injected bytecode in DispatcherServletAdvice — must remain public static.
     */
    // Cache getAttribute(String) Method per ClassLoader (keyed on the interface, stable per CL).
    private static final java.util.concurrent.ConcurrentHashMap<ClassLoader, java.lang.reflect.Method>
        GET_ATTR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isErrorDispatch(Object request) {
        try {
            // Strategy 1: DispatcherType == ERROR  (native container error dispatch)
            try {
                java.lang.reflect.Method gdt = request.getClass().getMethod("getDispatcherType");
                Object dt = gdt.invoke(request);
                if ("ERROR".equals(String.valueOf(dt))) return true;
            } catch (Throwable ignored) {}

            // Strategy 2: error attribute set by ErrorPageFilter (DispatcherType == FORWARD)
            // Get getAttribute() from the public ServletRequest interface — avoids JPMS
            // IllegalAccessException that can occur on non-exported Tomcat/Jetty internals.
            ClassLoader cl = request.getClass().getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            final ClassLoader resolveLoader = cl;
            java.lang.reflect.Method getAttribute = GET_ATTR_CACHE.computeIfAbsent(
                resolveLoader,
                loader -> {
                    for (String iface : new String[]{
                            "jakarta.servlet.ServletRequest",
                            "javax.servlet.ServletRequest"}) {
                        try {
                            return Class.forName(iface, false, loader)
                                       .getMethod("getAttribute", String.class);
                        } catch (Throwable ignored) {}
                    }
                    return null;
                });
            if (getAttribute == null) return false;
            return getAttribute.invoke(request, "jakarta.servlet.error.request_uri") != null
                || getAttribute.invoke(request, "javax.servlet.error.request_uri") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void onHttpInStart(String method, String path, String incomingTxId) {
        safeRun(() -> {
            String txId = incomingTxId;
            if (txId != null && !txId.isEmpty()) {
                TxIdHolder.set(txId);
                debugLog("[HTTP-IN] Adopted incoming txId from headers: " + txId);
            } else {
                if (TxIdHolder.get() != null) {
                    txId = TxIdHolder.get();
                    debugLog("[HTTP-IN] Reusing existing txId from context: " + txId);
                } else {
                    if (!TxIdGenerator.shouldSample()) {
                        debugLog("[HTTP-IN] Not sampling this request.");
                        return;
                    }
                    TxIdHolder.set(TxIdGenerator.generate());
                    txId = TxIdHolder.get();
                    debugLog("[HTTP-IN] Generated new txId: " + txId);
                }
            }
            if (txId != null) {
                TcpSender.send(createEvent(txId, TraceEventType.HTTP_IN_START, TraceCategory.HTTP, method + " " + path, null, true, null));
            }
        });
    }

    /**
     * Called on normal return from DispatcherServlet.doDispatch().
     * statusCode is read directly from the response object by injected bytecode.
     *
     * <p>Bytecode ABI: (Ljava/lang/String;Ljava/lang/String;IJ)V
     */
    public static void onHttpInEnd(String method, String path, int statusCode, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            boolean success = statusCode >= 200 && statusCode < 400;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", statusCode);
            if (!success) {
                extra.put("errorType", statusCode >= 500 ? "ServerError" : "ClientError");
                extra.put("errorMessage", "HTTP " + statusCode);
            }
            TcpSender.send(createEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP, method + " " + path, durationMs, success, extra));
            TxIdHolder.clear();
        });
    }

    /**
     * Called when DispatcherServlet.doDispatch() exits via an uncaught exception.
     * The throwable is captured by the injected bytecode (DUP before re-throw).
     *
     * <p>Bytecode ABI: (Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V
     */
    public static void onHttpInError(Throwable t, String method, String path, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("statusCode", -1);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", t != null ? truncateMessage(t.getMessage()) : null);
            TcpSender.send(createEvent(txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP, method + " " + path, durationMs, false, extra));
            TxIdHolder.clear();
        });
    }

    /**
     * Called when RestTemplate.doExecute() exits via exception.
     * Status code is extracted via reflection from HttpStatusCodeException if available.
     *
     * <p>Bytecode ABI: (Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V
     */
    public static void onHttpOutError(Throwable t, String method, String url, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            int statusCode = -1;
            if (t != null) {
                try {
                    java.lang.reflect.Method getStatusCode = t.getClass().getMethod("getStatusCode");
                    Object status = getStatusCode.invoke(t);
                    java.lang.reflect.Method valueMethod = status.getClass().getMethod("value");
                    statusCode = (int) valueMethod.invoke(status);
                } catch (Throwable ignored) {}
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method);
            extra.put("statusCode", statusCode);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", t != null ? truncateMessage(t.getMessage()) : null);
            TcpSender.send(createEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, url, durationMs, false, extra));
        });
    }

    public static void onHttpOut(String method, String uri, int statusCode, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("statusCode", statusCode);
            extra.put("method", method);
            TcpSender.send(createEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, uri, durationMs, statusCode >= 200 && statusCode < 400, extra));
        });
    }

    public static void onMqProduce(String brokerType, String topic, String key) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("brokerType", brokerType);
            extra.put("topic", topic);
            extra.put("key", key);
            TcpSender.send(createEvent(txId, TraceEventType.MQ_PRODUCE, TraceCategory.MQ, topic, null, true, extra));
        });
    }

    public static void onMqConsumeStart(String brokerType, String topic, String incomingTxId) {
        safeRun(() -> {
            String txId = incomingTxId;
            if (txId != null && !txId.isEmpty()) {
                TxIdHolder.set(txId);
                debugLog("[MQ-CONSUME] Adopted incoming txId from MQ headers: " + txId);
            } else {
                // If already set (e.g. by MessagingMessageListenerAdapter), use it.
                if (TxIdHolder.get() != null) {
                    txId = TxIdHolder.get();
                    debugLog("[MQ-CONSUME] Reusing existing txId from Adapter: " + txId);
                } else {
                    if (!TxIdGenerator.shouldSample()) {
                        debugLog("[MQ-CONSUME] Not sampling this message.");
                        return;
                    }
                    TxIdHolder.set(TxIdGenerator.generate());
                    txId = TxIdHolder.get();
                    debugLog("[MQ-CONSUME] Generated new txId (no upstream): " + txId);
                }
            }
            Map<String, Object> extra = new HashMap<>();
            extra.put("brokerType", brokerType);
            extra.put("topic", topic);
            TcpSender.send(createEvent(txId, TraceEventType.MQ_CONSUME_START, TraceCategory.MQ, topic, null, true, extra));
        });
    }

    /**
     * Called on normal return from a @KafkaListener method.
     *
     * <p>Bytecode ABI: (Ljava/lang/String;Ljava/lang/String;J)V
     */
    public static void onMqConsumeEnd(String brokerType, String topic, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("brokerType", brokerType);
            extra.put("topic", topic);
            TcpSender.send(createEvent(txId, TraceEventType.MQ_CONSUME_END, TraceCategory.MQ, topic, durationMs, true, extra));
            TxIdHolder.clear();
        });
    }

    /**
     * Called when a @KafkaListener method exits via an uncaught exception.
     *
     * <p>Bytecode ABI: (Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V
     */
    public static void onMqConsumeError(Throwable t, String brokerType, String topic, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("brokerType", brokerType);
            extra.put("topic", topic);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", t != null ? truncateMessage(t.getMessage()) : null);
            TcpSender.send(createEvent(txId, TraceEventType.MQ_CONSUME_END, TraceCategory.MQ, topic, durationMs, false, extra));
            TxIdHolder.clear();
        });
    }

    public static void onDbQueryStart(String sql) {
        emit(TraceEventType.DB_QUERY_START, TraceCategory.DB, truncate(sql), null, true, null);
    }

    /**
     * Called on normal return from a JDBC PreparedStatement execute method.
     *
     * <p>Bytecode ABI: (Ljava/lang/String;J)V
     */
    public static void onDbQueryEnd(String sql, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = null;
            if (durationMs > AgentConfig.getSlowQueryMs()) {
                extra = new HashMap<>();
                extra.put("slowQuery", true);
                extra.put("sql", truncate(sql));
            }
            TcpSender.send(createEvent(txId, TraceEventType.DB_QUERY_END, TraceCategory.DB, truncate(sql), durationMs, true, extra));
        });
    }

    /**
     * Called when a JDBC PreparedStatement execute method exits via an uncaught exception.
     *
     * <p>Bytecode ABI: (Ljava/lang/Throwable;Ljava/lang/String;J)V
     */
    public static void onDbQueryError(Throwable t, String sql, long durationMs) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", t != null ? truncateMessage(t.getMessage()) : null);
            TcpSender.send(createEvent(txId, TraceEventType.DB_QUERY_END, TraceCategory.DB, truncate(sql), durationMs, false, extra));
        });
    }

    public static void onFileRead(String path, long sizeBytes, long durationMs, boolean success) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            if (sizeBytes < AgentConfig.getMinSizeBytes()) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(createEvent(txId, TraceEventType.FILE_READ, TraceCategory.IO, path, durationMs, success, extra));
        });
    }

    public static void onFileWrite(String path, long sizeBytes, long durationMs, boolean success) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            if (sizeBytes < AgentConfig.getMinSizeBytes()) return;
            Map<String, Object> extra = new HashMap<>();
            extra.put("sizeBytes", sizeBytes);
            TcpSender.send(createEvent(txId, TraceEventType.FILE_WRITE, TraceCategory.IO, path, durationMs, success, extra));
        });
    }

    public static void onCacheGet(String key, boolean hit) {
        emit(hit ? TraceEventType.CACHE_HIT : TraceEventType.CACHE_MISS, TraceCategory.CACHE, key, null, true, null);
    }

    public static void onCacheSet(String key) {
        emit(TraceEventType.CACHE_SET, TraceCategory.CACHE, key, null, true, null);
    }

    public static void onCacheDel(String key) {
        emit(TraceEventType.CACHE_DEL, TraceCategory.CACHE, key, null, true, null);
    }

    /**
     * Allows plugins to publish custom events without coupling to TraceRuntime internals.
     */
    public static void emitCustomEvent(TraceEventType type, TraceCategory category,
                                       String target, Long durationMs, boolean success,
                                       Map<String, Object> extra) {
        emit(type, category, target, durationMs, success, extra);
    }

    /**
     * Wraps a WebClient Mono to record HTTP_OUT on completion.
     * Uses reflection to avoid a direct compile-time dependency on Reactor.
     */
    public static Object wrapWebClientExchange(Object mono, String method, String uri) {
        try {
            String txId = TxIdHolder.get();
            if (txId == null) return mono;

            try {
                Class.forName("reactor.core.publisher.Mono");
            } catch (ClassNotFoundException e) {
                return mono;
            }

            long startTime = System.currentTimeMillis();
            // Capture txId here on the calling thread; the Reactor scheduler may run the
            // callbacks on a different thread where TxIdHolder (ThreadLocal) is null.
            final String capturedTxId = txId;

            // doOnSuccess: extract status code via statusCode().value() reflection chain.
            // IMPORTANT: look up methods on the PUBLIC INTERFACES (ClientResponse, HttpStatusCode),
            // not on the concrete implementation classes (DefaultClientResponse, etc.).
            // Java module strong encapsulation blocks reflection on non-exported packages even for
            // public members; the public interface types are in exported packages and are safe.
            java.util.function.Consumer<Object> successConsumer = response -> {
                try {
                    long durationMs = System.currentTimeMillis() - startTime;
                    ClassLoader cl = response.getClass().getClassLoader();
                    Class<?> clientResponseIface = Class.forName(
                        "org.springframework.web.reactive.function.client.ClientResponse", false, cl);
                    java.lang.reflect.Method statusCodeMethod = clientResponseIface.getMethod("statusCode");
                    Object statusCodeObj = statusCodeMethod.invoke(response);
                    Class<?> httpStatusCodeIface = Class.forName(
                        "org.springframework.http.HttpStatusCode", false, cl);
                    java.lang.reflect.Method valueMethod = httpStatusCodeIface.getMethod("value");
                    int statusCode = (int) valueMethod.invoke(statusCodeObj);
                    emitHttpOut(capturedTxId, method, uri, statusCode, durationMs);
                } catch (Throwable t) {
                    emitHttpOut(capturedTxId, method, uri, -1, System.currentTimeMillis() - startTime, t);
                }
            };

            // doOnError: emit HTTP_OUT error event with exception details
            java.util.function.Consumer<Throwable> errorConsumer = err -> {
                long durationMs = System.currentTimeMillis() - startTime;
                emitHttpOutError(capturedTxId, err, method, uri, durationMs);
            };

            java.lang.reflect.Method doOnSuccessMethod =
                mono.getClass().getMethod("doOnSuccess", java.util.function.Consumer.class);
            Object monoWithSuccess = doOnSuccessMethod.invoke(mono, successConsumer);

            java.lang.reflect.Method doOnErrorMethod =
                monoWithSuccess.getClass().getMethod("doOnError", java.util.function.Consumer.class);
            return doOnErrorMethod.invoke(monoWithSuccess, errorConsumer);
        } catch (Throwable t) {
            return mono;
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Used by WebClient callbacks that run on a Reactor thread (TxIdHolder is unavailable there). */
    private static void emitHttpOut(String txId, String method, String uri, int statusCode, long durationMs) {
        emitHttpOut(txId, method, uri, statusCode, durationMs, null);
    }

    /**
     * Variant that includes the cause when statusCode could not be determined (e.g. reflection
     * failure reading the response status code). The cause fields help diagnose why -1 appears.
     * Also synthesises errorType/errorMessage for non-2xx responses even when cause is null.
     */
    private static void emitHttpOut(String txId, String method, String uri, int statusCode, long durationMs, Throwable cause) {
        safeRun(() -> {
            if (txId == null) return;
            boolean success = statusCode >= 200 && statusCode < 400;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method);
            extra.put("statusCode", statusCode);
            if (cause != null) {
                extra.put("errorType", cause.getClass().getSimpleName());
                extra.put("errorMessage", truncateMessage(cause.getMessage()));
            } else if (!success) {
                extra.put("errorType", statusCode >= 500 ? "ServerError" : "ClientError");
                extra.put("errorMessage", "HTTP " + statusCode);
            }
            TcpSender.send(createEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, uri, durationMs, success, extra));
        });
    }

    /** Used by WebClient error callbacks that run on a Reactor thread. */
    private static void emitHttpOutError(String txId, Throwable t, String method, String uri, long durationMs) {
        safeRun(() -> {
            if (txId == null) return;
            // Try to extract the actual HTTP status code from WebClientResponseException.
            // Use the public interface HttpStatusCode.value() to avoid JPMS access issues.
            int statusCode = -1;
            if (t != null) {
                try {
                    ClassLoader cl = t.getClass().getClassLoader();
                    Class<?> webclientExClass = Class.forName(
                        "org.springframework.web.reactive.function.client.WebClientResponseException", false, cl);
                    if (webclientExClass.isInstance(t)) {
                        Class<?> httpStatusCodeIface = Class.forName(
                            "org.springframework.http.HttpStatusCode", false, cl);
                        java.lang.reflect.Method getStatusCode = webclientExClass.getMethod("getStatusCode");
                        Object statusCodeObj = getStatusCode.invoke(t);
                        java.lang.reflect.Method valueMethod = httpStatusCodeIface.getMethod("value");
                        statusCode = (int) valueMethod.invoke(statusCodeObj);
                    }
                } catch (Throwable ignored) {}
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("method", method);
            extra.put("statusCode", statusCode);
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", t != null ? truncateMessage(t.getMessage()) : null);
            TcpSender.send(createEvent(txId, TraceEventType.HTTP_OUT, TraceCategory.HTTP, uri, durationMs, false, extra));
        });
    }

    private static void emit(TraceEventType type, TraceCategory category, String target,
                              Long durationMs, boolean success, Map<String, Object> extra) {
        safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            TcpSender.send(createEvent(txId, type, category, target, durationMs, success, extra));
        });
    }

    private static TraceEvent createEvent(String txId, TraceEventType type, TraceCategory category,
                                          String target, Long durationMs, boolean success,
                                          Map<String, Object> extraInfo) {
        return new TraceEvent(
            UUID.randomUUID().toString(),
            txId,
            type,
            category,
            AgentConfig.getServerName(),
            target,
            durationMs,
            success,
            System.currentTimeMillis(),
            extraInfo != null ? extraInfo : new HashMap<>()
        );
    }

    private static void safeRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            AgentLogger.error("Error in agent runtime", t);
        }
    }

    private static void debugLog(String message) {
        AgentLogger.debug(message);
    }

    private static final int SQL_MAX_LENGTH = 1000;
    private static final int MSG_MAX_LENGTH = 200;

    private static String truncate(String text) {
        if (text == null || text.length() <= SQL_MAX_LENGTH) return text;
        return text.substring(0, SQL_MAX_LENGTH) + "...";
    }

    private static String truncateMessage(String msg) {
        if (msg == null || msg.length() <= MSG_MAX_LENGTH) return msg;
        return msg.substring(0, MSG_MAX_LENGTH) + "...";
    }
}
