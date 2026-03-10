package org.example.agent.plugin.http;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.example.agent.AgentInitializer;
import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.agent.plugin.ReflectionUtils;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * HttpPlugin: instruments DispatcherServlet, DispatcherHandler (WebFlux), RestTemplate,
 * WebClient, and HttpServletRequest.startAsync() for HTTP tracing.
 *
 * <p>Uses ByteBuddy @Advice inline instrumentation — no raw ASM required.
 * All servlet/framework types are accessed via reflection helpers to avoid
 * compile-time dependencies on javax/jakarta servlet APIs.
 */
public class HttpPlugin implements TracerPlugin {

    @Override public String pluginId() { return "http"; }
    @Override public boolean requiresBootstrapSearch() { return true; }

    @Override
    public AgentBuilder install(AgentBuilder builder) {
        if (!isEnabled(null)) return builder;

        ClassFileLocator agentLocator = AgentInitializer.getAgentLocator();

        return builder
            // (1) DispatcherServlet — single advice for both Javax and Jakarta
            //     doDispatch takes 2 args (request, response) regardless of servlet package
            .type(nameEndsWith("DispatcherServlet")
                .and(nameStartsWith("org.springframework.web.servlet.")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(DispatcherServletAdvice.class, agentLocator)
                    .on(named("doDispatch").and(takesArguments(2)))))
            // (2) DispatcherHandler — WebFlux
            .type(named("org.springframework.web.reactive.DispatcherHandler"))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(DispatcherHandlerAdvice.class, agentLocator)
                    .on(named("handle")
                        .and(takesArgument(0,
                            named("org.springframework.web.server.ServerWebExchange"))))))
            // (3) RestTemplate — Spring 5/6.0 (4-arg doExecute)
            .type(nameStartsWith("org.springframework.web.client.")
                .or(nameStartsWith("org.springframework.http.client.support.")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(RestTemplateAdvice4Args.class, agentLocator)
                    .on(named("doExecute")
                        .and(takesArguments(4))
                        .and(takesArgument(0, named("java.net.URI"))))))
            // (4) RestTemplate — Spring 6.1+ (5-arg doExecute)
            .type(nameStartsWith("org.springframework.web.client.")
                .or(nameStartsWith("org.springframework.http.client.support.")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(RestTemplateAdvice5Args.class, agentLocator)
                    .on(named("doExecute")
                        .and(takesArguments(5))
                        .and(takesArgument(0, named("java.net.URI"))))))
            // (5) RestTemplate createRequest — inject txId/spanId into outgoing request headers
            .type(nameStartsWith("org.springframework.web.client.")
                .or(nameStartsWith("org.springframework.http.client.support.")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(RestTemplateCreateRequestAdvice.class, agentLocator)
                    .on(named("createRequest")
                        .and(takesArguments(2))
                        .and(returns(named("org.springframework.http.client.ClientHttpRequest"))))))
            // (6) WebClient exchange — inject headers + wrap Mono
            .type(nameEndsWith("DefaultExchangeFunction")
                .or(nameContains("ExchangeFunctions")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(WebClientAdvice.class, agentLocator)
                    .on(named("exchange")
                        .and(takesArgument(0,
                            named("org.springframework.web.reactive.function.client.ClientRequest"))))))
            // (7) HttpServletRequest startAsync — register async completion listener
            .type(nameStartsWith("javax.servlet.")
                .or(nameStartsWith("jakarta.servlet."))
                .or(nameStartsWith("org.apache.catalina."))
                .or(nameStartsWith("org.springframework.mock.web.")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(StartAsyncAdvice.class, agentLocator)
                    .on(named("startAsync"))));
    }

    // -----------------------------------------------------------------------
    // DispatcherServlet Advice (works for both Javax and Jakarta via Object params)
    // -----------------------------------------------------------------------

    public static class DispatcherServletAdvice {

        @Advice.OnMethodEnter
        static void enter(
            @Advice.Argument(0) Object request,
            @Advice.Local("startTime") long startTime,
            @Advice.Local("isTracked") boolean isTracked
        ) {
            startTime = System.currentTimeMillis();
            isTracked = true;

            if (TraceRuntime.isSecondaryDispatch(request)) {
                TraceRuntime.restoreContext(request);
                isTracked = TxIdHolder.get() != null;
                return;
            }

            // Primary dispatch: start new trace
            boolean forceTrace = "true".equalsIgnoreCase(
                HttpPlugin.getRequestHeader(request, AgentConfig.getForceSampleHeader()));
            TraceRuntime.onHttpInStart(
                request,
                HttpPlugin.getRequestMethod(request),
                HttpPlugin.getRequestURI(request),
                HttpPlugin.getRequestHeader(request, AgentConfig.getHeaderKey()),
                HttpPlugin.getRequestHeader(request, "X-Span-Id"),
                forceTrace
            );
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("startTime") long startTime,
            @Advice.Local("isTracked") boolean isTracked
        ) {
            if (!isTracked) return;
            long durationMs = System.currentTimeMillis() - startTime;

            if (thrown != null) {
                TraceRuntime.onHttpInError(thrown,
                    HttpPlugin.getRequestMethod(request),
                    HttpPlugin.getRequestURI(request), durationMs);
                return;
            }
            if (HttpPlugin.isAsyncStarted(request)) {
                TraceRuntime.registerAsyncListener(request,
                    HttpPlugin.getRequestMethod(request),
                    HttpPlugin.getRequestURI(request), startTime);
                TxIdHolder.clear();
                SpanIdHolder.clear();
            } else {
                TraceRuntime.onHttpInEnd(
                    HttpPlugin.getRequestMethod(request),
                    HttpPlugin.getRequestURI(request),
                    HttpPlugin.getResponseStatus(response), durationMs);
            }
        }
    }

    // -----------------------------------------------------------------------
    // DispatcherHandler Advice (WebFlux)
    // -----------------------------------------------------------------------

    public static class DispatcherHandlerAdvice {

        @Advice.OnMethodEnter
        static void enter(
            @Advice.Argument(0) Object exchange,
            @Advice.Local("startTime") long startTime
        ) {
            startTime = System.currentTimeMillis();
            TraceRuntime.onWebFluxHandleStart(exchange);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Argument(0) Object exchange,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object mono,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("startTime") long startTime
        ) {
            if (thrown != null) {
                TraceRuntime.onWebFluxHandleSyncError();
                return;
            }
            mono = TraceRuntime.wrapWebFluxHandle(mono, exchange, startTime);
            TxIdHolder.clear();
            SpanIdHolder.clear();
        }
    }

    // -----------------------------------------------------------------------
    // RestTemplate Advice — 4-arg doExecute (Spring 5 / 6.0)
    //   doExecute(URI url, HttpMethod method, RequestCallback, ResponseExtractor)
    // -----------------------------------------------------------------------

    public static class RestTemplateAdvice4Args {

        @Advice.OnMethodEnter
        static void enter(@Advice.Local("startTime") long startTime) {
            startTime = System.currentTimeMillis();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Argument(0) java.net.URI uri,
            @Advice.Argument(1) Object httpMethod,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("startTime") long startTime
        ) {
            long durationMs = System.currentTimeMillis() - startTime;
            String method = httpMethod instanceof Enum
                ? ((Enum<?>) httpMethod).name() : String.valueOf(httpMethod);
            if (thrown != null) {
                TraceRuntime.onHttpOutError(thrown, method, uri.toString(), durationMs);
            } else {
                TraceRuntime.onHttpOut(method, uri.toString(), 200, durationMs);
            }
        }
    }

    // -----------------------------------------------------------------------
    // RestTemplate Advice — 5-arg doExecute (Spring 6.1+)
    //   doExecute(URI url, UriTemplate uriTemplate, HttpMethod method, RequestCallback, ResponseExtractor)
    // -----------------------------------------------------------------------

    public static class RestTemplateAdvice5Args {

        @Advice.OnMethodEnter
        static void enter(@Advice.Local("startTime") long startTime) {
            startTime = System.currentTimeMillis();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Argument(0) java.net.URI uri,
            @Advice.Argument(2) Object httpMethod,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("startTime") long startTime
        ) {
            long durationMs = System.currentTimeMillis() - startTime;
            String method = httpMethod instanceof Enum
                ? ((Enum<?>) httpMethod).name() : String.valueOf(httpMethod);
            if (thrown != null) {
                TraceRuntime.onHttpOutError(thrown, method, uri.toString(), durationMs);
            } else {
                TraceRuntime.onHttpOut(method, uri.toString(), 200, durationMs);
            }
        }
    }

    // -----------------------------------------------------------------------
    // RestTemplate createRequest — inject trace headers into outgoing request
    // -----------------------------------------------------------------------

    public static class RestTemplateCreateRequestAdvice {

        @Advice.OnMethodExit
        static void exit(@Advice.Return Object request) {
            HttpPlugin.injectHeadersToRequest(request, TxIdHolder.get(), SpanIdHolder.get());
        }
    }

    // -----------------------------------------------------------------------
    // WebClient Advice — inject headers + wrap Mono with trace callbacks
    // -----------------------------------------------------------------------

    public static class WebClientAdvice {

        @Advice.OnMethodEnter
        static void enter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request
        ) {
            request = HttpPlugin.rebuildClientRequestWithHeaders(
                request, TxIdHolder.get(), SpanIdHolder.get());
        }

        @Advice.OnMethodExit
        static void exit(
            @Advice.Argument(0) Object request,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object mono
        ) {
            String method = HttpPlugin.getClientRequestMethod(request);
            String url = HttpPlugin.getClientRequestUrl(request);
            mono = TraceRuntime.wrapWebClientExchange(mono, method, url);
        }
    }

    // -----------------------------------------------------------------------
    // StartAsync Advice — register AsyncListener to record HTTP_IN_END on async completion
    // -----------------------------------------------------------------------

    public static class StartAsyncAdvice {

        @Advice.OnMethodExit
        static void exit(@Advice.This Object request) {
            TraceRuntime.registerAsyncListenerFromRequest(request);
        }
    }

    // -----------------------------------------------------------------------
    // Static helpers — reflection-based access to servlet/framework types
    // -----------------------------------------------------------------------

    public static String getRequestMethod(Object request) {
        if (request == null) return "UNKNOWN";
        return ReflectionUtils.invokeMethod(request, "getMethod")
            .map(Object::toString).orElse("UNKNOWN");
    }

    public static String getRequestURI(Object request) {
        if (request == null) return "/unknown";
        return ReflectionUtils.invokeMethod(request, "getRequestURI")
            .map(Object::toString).orElse("/unknown");
    }

    public static String getRequestHeader(Object request, String name) {
        if (request == null || name == null) return null;
        return ReflectionUtils.invokeMethod(request, "getHeader", name)
            .map(Object::toString).orElse(null);
    }

    public static int getResponseStatus(Object response) {
        if (response == null) return 200;
        return ReflectionUtils.invokeMethod(response, "getStatus")
            .map(s -> s instanceof Integer ? (Integer) s : Integer.parseInt(s.toString()))
            .orElse(200);
    }

    public static boolean isAsyncStarted(Object request) {
        if (request == null) return false;
        return ReflectionUtils.invokeMethod(request, "isAsyncStarted")
            .map(v -> Boolean.TRUE.equals(v)).orElse(false);
    }

    public static String getClientRequestMethod(Object request) {
        if (request == null) return "UNKNOWN";
        return ReflectionUtils.invokeMethod(request, "method")
            .map(m -> m instanceof Enum ? ((Enum<?>) m).name() : m.toString())
            .orElse("UNKNOWN");
    }

    public static String getClientRequestUrl(Object request) {
        if (request == null) return "unknown-url";
        return ReflectionUtils.invokeMethod(request, "url")
            .map(Object::toString).orElse("unknown-url");
    }

    public static void injectHeadersToRequest(Object request, String txId, String spanId) {
        if (txId == null || request == null) return;
        try {
            // ClientHttpRequest.getHeaders()
            java.lang.reflect.Method getHeadersMethod = request.getClass().getMethod("getHeaders");
            Object headers = getHeadersMethod.invoke(request);
            if (headers == null) return;

            // HttpHeaders.add(String, String)
            java.lang.reflect.Method addMethod = headers.getClass().getMethod("add", String.class, String.class);
            addMethod.invoke(headers, AgentConfig.getHeaderKey(), txId);
            if (spanId != null) {
                addMethod.invoke(headers, "X-Span-Id", spanId);
            }
            AgentLogger.debug("[HTTP-OUT] Injected headers: " + AgentConfig.getHeaderKey() + "=" + txId);
        } catch (Throwable t) {
            AgentLogger.debug("[HTTP-OUT] Failed to inject headers via reflection: " + t.getMessage());
            // Fallback to ReflectionUtils
            ReflectionUtils.invokeMethod(request, "getHeaders").ifPresent(headers -> {
                ReflectionUtils.invokeMethod(headers, "add", AgentConfig.getHeaderKey(), txId);
                if (spanId != null) ReflectionUtils.invokeMethod(headers, "add", "X-Span-Id", spanId);
            });
        }
    }

    public static Object rebuildClientRequestWithHeaders(Object request, String txId, String spanId) {
        if (txId == null || request == null) return request;
        try {
            AgentLogger.debug("[HTTP] WebClient: Header Propagation (txId=" + txId + ")");
            ClassLoader cl = request.getClass().getClassLoader();
            Class<?> crClass = Class.forName(
                "org.springframework.web.reactive.function.client.ClientRequest", false, cl);
            java.lang.reflect.Method from = crClass.getMethod("from", crClass);
            Object builderObj = from.invoke(null, request);
            Class<?> builderClass = Class.forName(
                "org.springframework.web.reactive.function.client.ClientRequest$Builder", false, cl);
            java.lang.reflect.Method headerMethod =
                builderClass.getMethod("header", String.class, String[].class);
            headerMethod.invoke(builderObj, AgentConfig.getHeaderKey(), new String[]{txId});
            if (spanId != null) {
                headerMethod.invoke(builderObj, "X-Span-Id", new String[]{spanId});
            }
            return builderClass.getMethod("build").invoke(builderObj);
        } catch (Throwable t) {
            return request;
        }
    }
}
