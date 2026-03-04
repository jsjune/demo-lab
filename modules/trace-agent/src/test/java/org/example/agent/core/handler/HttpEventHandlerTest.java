package org.example.agent.core.handler;

import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: HttpEventHandler 단위 테스트")
class HttpEventHandlerTest {

    private List<TraceEvent> capturedEvents;
    private MockedStatic<TcpSender> tcpMock;

    @BeforeEach
    void setUp() {
        capturedEvents = new ArrayList<>();
        tcpMock = mockStatic(TcpSender.class);
        tcpMock.when(() -> TcpSender.send(any(TraceEvent.class))).thenAnswer(invocation -> {
            capturedEvents.add(invocation.getArgument(0));
            return null;
        });
        clearTraceRuntimeReflectionCaches();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @Test
    @DisplayName("T-01: onInEnd — 2xx statusCode → success=true, extra[statusCode] 포함")
    void onInEnd_statusCode2xx_successTrue() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        HttpEventHandler.onInEnd("GET", "/api", 200, 10L);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.HTTP_IN_END, e.type());
        assertTrue(e.success());
        assertEquals(200, e.extraInfo().get("statusCode"));
        assertNull(TxIdHolder.get());
        assertNull(SpanIdHolder.get());
    }

    @Test
    @DisplayName("T-02: onInEnd — 5xx statusCode → success=false")
    void onInEnd_statusCode5xx_successFalse() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        HttpEventHandler.onInEnd("GET", "/api", 500, 10L);

        assertEquals(1, capturedEvents.size());
        assertFalse(capturedEvents.get(0).success());
        assertEquals(500, capturedEvents.get(0).extraInfo().get("statusCode"));
    }

    @Test
    @DisplayName("T-03: onWfSyncError — TxIdHolder, SpanIdHolder 모두 null로 클리어")
    void onWfSyncError_clearsContext() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");

        HttpEventHandler.onWfSyncError();

        assertNull(TxIdHolder.get());
        assertNull(SpanIdHolder.get());
    }

    @Test
    @DisplayName("T-04: onInEndAsync(onError) — throwable 타입/메시지가 extraInfo에 기록")
    void onInEndAsync_onError_recordsReason() {
        FakeRequest req = new FakeRequest();
        Throwable t = new IllegalAccessException("intentional-async-error");

        HttpEventHandler.onInEndAsync("tx-async", "span-async", "GET", "/async",
            System.currentTimeMillis() - 5, req, "onError", new FakeAsyncEvent(t, null));

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.HTTP_IN_END, e.type());
        assertFalse(e.success());
        assertEquals("IllegalAccessException", e.extraInfo().get("errorType"));
        assertEquals("intentional-async-error", e.extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-05: onInEndAsync(onTimeout) — statusCode=504, timeout=true")
    void onInEndAsync_onTimeout_setsTimeoutStatus() {
        FakeRequest req = new FakeRequest();

        HttpEventHandler.onInEndAsync("tx-timeout", "span-timeout", "GET", "/async",
            System.currentTimeMillis() - 5, req, "onTimeout", new FakeAsyncEvent(null, null));

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(504, e.extraInfo().get("statusCode"));
        assertEquals(Boolean.TRUE, e.extraInfo().get("timeout"));
        assertFalse(e.success());
    }

    @Test
    @DisplayName("T-06: onInEndAsync(onComplete) + suppliedResponse status 추출")
    void onInEndAsync_onComplete_extractsSuppliedResponseStatus() {
        FakeRequest req = new FakeRequest();

        HttpEventHandler.onInEndAsync("tx-complete", "span-complete", "GET", "/async",
            System.currentTimeMillis() - 5, req, "onComplete", new FakeAsyncEvent(null, new FakeSuppliedResponse(204)));

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(204, e.extraInfo().get("statusCode"));
        assertTrue(e.success());
    }

    @Test
    @DisplayName("T-07: onInEndAsync — 이미 종료된 요청은 중복 전송하지 않음")
    void onInEndAsync_finishedRequest_doesNotEmitTwice() {
        FakeRequest req = new FakeRequest();
        req.setAttribute("__TRACE_FINISHED__", Boolean.TRUE);

        HttpEventHandler.onInEndAsync("tx-finish", "span-finish", "GET", "/async",
            System.currentTimeMillis() - 5, req, "onComplete", null);

        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-08: registerFromRequest — 필수 속성 누락 시 no-op")
    void registerFromRequest_missingAttributes_noop() {
        FakeRequest req = new FakeRequest();
        HttpEventHandler.registerFromRequest(req);
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-09: onOutError — txId 있을 때 HTTP_OUT 실패 이벤트 전송")
    void onOutError_emitsEventWhenTxExists() {
        TxIdHolder.set("tx-out-err");
        HttpEventHandler.onOutError(new RuntimeException("outbound-fail"), "GET", "http://localhost/test", 12L);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.HTTP_OUT, e.type());
        assertFalse(e.success());
        assertEquals("RuntimeException", e.extraInfo().get("errorType"));
        assertEquals("outbound-fail", e.extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-10: onOutError — txId 없으면 no-op")
    void onOutError_withoutTxId_noop() {
        HttpEventHandler.onOutError(new RuntimeException("x"), "GET", "http://localhost/test", 1L);
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    @DisplayName("T-11: wrapWebClient — txId 없으면 원본 mono 반환")
    void wrapWebClient_withoutTxId_returnsOriginal() {
        FakeMono mono = new FakeMono();
        Object wrapped = HttpEventHandler.wrapWebClient(mono, "GET", "http://localhost/test");
        assertSame(mono, wrapped);
    }

    @Test
    @DisplayName("T-12: wrapWebClient — success/error 콜백에서 HTTP_OUT 전송")
    void wrapWebClient_callbacks_emitEvents() {
        TxIdHolder.set("tx-webclient");
        SpanIdHolder.set("span-parent");
        FakeMono mono = new FakeMono();

        Object wrapped = HttpEventHandler.wrapWebClient(mono, "GET", "http://localhost/test");
        assertSame(mono, wrapped);

        mono.emitSuccess(new Object());
        mono.emitError(new IllegalStateException("webclient-fail"));

        assertEquals(2, capturedEvents.size());
        assertEquals(TraceEventType.HTTP_OUT, capturedEvents.get(0).type());
        assertEquals(TraceEventType.HTTP_OUT, capturedEvents.get(1).type());
        assertFalse(capturedEvents.get(1).success());
        assertEquals("IllegalStateException", capturedEvents.get(1).extraInfo().get("errorType"));
        assertEquals("webclient-fail", capturedEvents.get(1).extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-13: wrapWebClient(ClientResponse) — statusCode 추출 성공 경로")
    void wrapWebClient_clientResponse_extractsStatusCode() {
        TxIdHolder.set("tx-webclient-status");
        SpanIdHolder.set("span-parent");

        Mono<ClientResponse> mono = Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());
        Object wrapped = HttpEventHandler.wrapWebClient(mono, "GET", "http://localhost/client-response");
        assertInstanceOf(Mono.class, wrapped);
        ((Mono<?>) wrapped).block();

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.HTTP_OUT, e.type());
        assertTrue(e.success());
        assertEquals(202, e.extraInfo().get("statusCode"));
    }

    @Test
    @DisplayName("T-14: onWfStart + wrapWfHandle(success) — HTTP_IN_START/END 전송")
    void webFluxStartAndHandle_success() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/wf/test")
            .header("X-Tx-Id", "tx-wf-1")
            .header("X-Span-Id", "span-upstream")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(req);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        HttpEventHandler.onWfStart(exchange);
        assertEquals("tx-wf-1", TxIdHolder.get());

        Object wrapped = HttpEventHandler.wrapWfHandle(Mono.empty(), exchange, System.currentTimeMillis() - 5);
        assertInstanceOf(Mono.class, wrapped);
        ((Mono<?>) wrapped).block();

        assertTrue(capturedEvents.size() >= 2);
        assertEquals(TraceEventType.HTTP_IN_START, capturedEvents.get(0).type());
        assertEquals(TraceEventType.HTTP_IN_END, capturedEvents.get(capturedEvents.size() - 1).type());
        assertEquals(200, capturedEvents.get(capturedEvents.size() - 1).extraInfo().get("statusCode"));
        assertNull(TxIdHolder.get());
        assertNull(SpanIdHolder.get());
    }

    @Test
    @DisplayName("T-15: wrapWfHandle(error) — HTTP_IN_END 실패 이벤트 전송")
    void webFluxHandle_errorPath() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/wf/error")
            .header("X-Tx-Id", "tx-wf-err")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(req);

        HttpEventHandler.onWfStart(exchange);
        Object wrapped = HttpEventHandler.wrapWfHandle(
            Mono.error(new IllegalStateException("wf-fail")), exchange, System.currentTimeMillis() - 5);
        assertInstanceOf(Mono.class, wrapped);
        assertThrows(IllegalStateException.class, () -> ((Mono<?>) wrapped).block());

        TraceEvent end = capturedEvents.stream()
            .filter(e -> e.type() == TraceEventType.HTTP_IN_END)
            .reduce((a, b) -> b)
            .orElseThrow();
        assertFalse(end.success());
        assertEquals("IllegalStateException", end.extraInfo().get("errorType"));
        assertEquals("wf-fail", end.extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-16: register — AsyncListener 등록 후 terminal 이벤트 1회만 전송")
    void register_asyncListenerEmitsOnce() throws IOException {
        AsyncCapableRequest req = new AsyncCapableRequest();
        req.setAttribute("__TRACE_TX_ID__", "tx-async-register");
        req.setAttribute("__TRACE_SPAN_ID__", "span-async-register");

        HttpEventHandler.register(req, "GET", "/async/register", System.currentTimeMillis() - 5);
        assertNotNull(req.asyncContext.listener);
        assertEquals(Boolean.TRUE, req.getAttribute("__TRACE_ASYNC_REGISTERED__"));

        AsyncEvent complete = Mockito.mock(AsyncEvent.class);
        req.asyncContext.listener.onComplete(complete);
        req.asyncContext.listener.onError(Mockito.mock(AsyncEvent.class));

        long endCount = capturedEvents.stream().filter(e -> e.type() == TraceEventType.HTTP_IN_END).count();
        assertEquals(1, endCount);
    }

    @Test
    @DisplayName("T-17: registerFromRequest — request 속성 기반으로 AsyncListener 등록")
    void registerFromRequest_registersFromAttributes() {
        AsyncCapableRequest req = new AsyncCapableRequest();
        req.setAttribute("__TRACE_METHOD__", "GET");
        req.setAttribute("__TRACE_PATH__", "/async/from-request");
        req.setAttribute("__TRACE_START_TIME__", System.currentTimeMillis() - 5);
        req.setAttribute("__TRACE_TX_ID__", "tx-from-request");
        req.setAttribute("__TRACE_SPAN_ID__", "span-from-request");

        HttpEventHandler.registerFromRequest(req);

        assertNotNull(req.asyncContext.listener);
        assertEquals(Boolean.TRUE, req.getAttribute("__TRACE_ASYNC_REGISTERED__"));
    }

    @Test
    @DisplayName("T-18: private resolveAsyncStatusCode fallback 분기 검증")
    void privateResolveAsyncStatusCode_fallbackBranches() throws Exception {
        Method m = HttpEventHandler.class.getDeclaredMethod("resolveAsyncStatusCode", String.class, Object.class);
        m.setAccessible(true);

        assertEquals(500, m.invoke(null, "onError", new Object()));
        assertEquals(504, m.invoke(null, "onTimeout", new Object()));
        assertEquals(200, m.invoke(null, "onComplete", new Object()));
    }

    @Test
    @DisplayName("T-19: private tryExtractAsyncResponseStatus non-number/null 경로")
    void privateTryExtractAsyncResponseStatus_nonNumberAndNull() throws Exception {
        Method m = HttpEventHandler.class.getDeclaredMethod("tryExtractAsyncResponseStatus", Object.class);
        m.setAccessible(true);

        assertNull(m.invoke(null, new FakeAsyncEvent(null, new Object() {
            @SuppressWarnings("unused")
            public String getStatus() { return "ok"; }
        })));
        assertNull(m.invoke(null, new FakeAsyncEvent(null, null)));
    }

    @Test
    @DisplayName("T-20: private injectReactorContext success 경로")
    void privateInjectReactorContext_successPath() throws Exception {
        Method m = HttpEventHandler.class.getDeclaredMethod(
            "injectReactorContext", Object.class, String.class, String.class, ClassLoader.class);
        m.setAccessible(true);

        Mono<String> mono = Mono.just("x");
        Object out = m.invoke(null, mono, "tx-ctx", "span-ctx", mono.getClass().getClassLoader());

        assertInstanceOf(Mono.class, out);
        assertEquals("x", ((Mono<?>) out).block());
    }

    @Test
    @DisplayName("T-21: private extractWebFluxMethodPath fallback 경로")
    void privateExtractWebFluxMethodPath_fallbackPath() throws Exception {
        Method m = HttpEventHandler.class.getDeclaredMethod("extractWebFluxMethodPath", Object.class, ClassLoader.class);
        m.setAccessible(true);

        String[] out = (String[]) m.invoke(null, new Object(), getClass().getClassLoader());
        assertEquals("UNKNOWN", out[0]);
        assertEquals("/", out[1]);
    }

    @Test
    @DisplayName("T-22: private resolveAndInvoke iface 미존재 시 null")
    void privateResolveAndInvoke_missingInterface_returnsNull() throws Exception {
        Method m = HttpEventHandler.class.getDeclaredMethod(
            "resolveAndInvoke", java.util.concurrent.ConcurrentHashMap.class, ClassLoader.class,
            String.class, String.class, Object.class);
        m.setAccessible(true);

        Object out = m.invoke(null, new java.util.concurrent.ConcurrentHashMap<>(), getClass().getClassLoader(),
            "no.such.Interface", "x", new Object());
        assertNull(out);
    }

    @Test
    @DisplayName("T-23: private extractStatusCode class-not-found 경로는 -1")
    void privateExtractStatusCode_fallbackMinusOne() throws Exception {
        Method m = HttpEventHandler.class.getDeclaredMethod("extractStatusCode", Object.class);
        m.setAccessible(true);
        clearHttpEventHandlerCaches();

        Object response = new Object();
        int out = (int) m.invoke(null, response);
        assertEquals(-1, out);
    }

    @Test
    @DisplayName("T-24: private extractWebFluxResponseStatus class-not-found 경로는 -1")
    void privateExtractWebFluxResponseStatus_fallbackMinusOne() throws Exception {
        Method m = HttpEventHandler.class.getDeclaredMethod("extractWebFluxResponseStatus", Object.class, ClassLoader.class);
        m.setAccessible(true);
        clearHttpEventHandlerCaches();

        int out = (int) m.invoke(null, new Object(), getClass().getClassLoader());
        assertEquals(-1, out);
    }

    static class FakeRequest {
        private final Map<String, Object> attrs = new HashMap<>();

        public Object getAttribute(String name) {
            return attrs.get(name);
        }

        public void setAttribute(String name, Object value) {
            attrs.put(name, value);
        }

        public Object getAsyncContext() {
            return null;
        }
    }

    static class FakeAsyncEvent {
        private final Throwable throwable;
        private final Object suppliedResponse;

        FakeAsyncEvent(Throwable throwable, Object suppliedResponse) {
            this.throwable = throwable;
            this.suppliedResponse = suppliedResponse;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public Object getSuppliedResponse() {
            return suppliedResponse;
        }
    }

    static class FakeSuppliedResponse {
        private final int status;

        FakeSuppliedResponse(int status) {
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    static class FakeMono {
        private Consumer<Object> successConsumer;
        private Consumer<Throwable> errorConsumer;

        public FakeMono doOnSuccess(Consumer<Object> c) {
            this.successConsumer = c;
            return this;
        }

        public FakeMono doOnError(Consumer<Throwable> c) {
            this.errorConsumer = c;
            return this;
        }

        void emitSuccess(Object response) {
            if (successConsumer != null) {
                successConsumer.accept(response);
            }
        }

        void emitError(Throwable t) {
            if (errorConsumer != null) {
                errorConsumer.accept(t);
            }
        }
    }

    static class AsyncCapableRequest extends FakeRequest {
        private final AsyncContextStub asyncContext = new AsyncContextStub();

        @Override
        public Object getAsyncContext() {
            return asyncContext;
        }
    }

    static class AsyncContextStub {
        private AsyncListener listener;

        public void addListener(AsyncListener l) {
            this.listener = l;
        }
    }

    @SuppressWarnings("unchecked")
    private void clearTraceRuntimeReflectionCaches() {
        try {
            Class<?> rt = Class.forName("org.example.agent.core.TraceRuntime");
            java.lang.reflect.Field getField = rt.getDeclaredField("GET_ATTR_CACHE");
            java.lang.reflect.Field setField = rt.getDeclaredField("SET_ATTR_CACHE");
            getField.setAccessible(true);
            setField.setAccessible(true);
            ((Map<?, ?>) getField.get(null)).clear();
            ((Map<?, ?>) setField.get(null)).clear();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private void clearHttpEventHandlerCaches() {
        try {
            Class<?> c = HttpEventHandler.class;
            String[] names = {
                "HTTP_STATUS_CODE_METHOD_CACHE", "HTTP_STATUS_VALUE_METHOD_CACHE", "WC_EXCEPTION_STATUS_METHOD_CACHE",
                "WF_GET_REQUEST_CACHE", "WF_GET_RESPONSE_CACHE", "WF_REQ_METHOD_CACHE",
                "WF_REQ_URI_CACHE", "WF_REQ_HEADERS_CACHE", "WF_RESP_STATUS_CACHE"
            };
            for (String n : names) {
                java.lang.reflect.Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                ((Map<?, ?>) f.get(null)).clear();
            }
        } catch (Exception ignored) {
        }
    }
}
