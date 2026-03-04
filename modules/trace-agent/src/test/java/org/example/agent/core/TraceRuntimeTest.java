package org.example.agent.core;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceRuntimeTest {

    private static List<TraceEvent> capturedEvents;
    private static MockedStatic<TcpSender> tcpMock;

    @BeforeEach
    void setup() {
        capturedEvents = new ArrayList<>();
        tcpMock = mockStatic(TcpSender.class);
        tcpMock.when(() -> TcpSender.send(any(TraceEvent.class))).thenAnswer(invocation -> {
            capturedEvents.add(invocation.getArgument(0));
            return null;
        });
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
    @DisplayName("onHttpInStart should adopt incoming txId")
    void onHttpInStart_AdoptTxId() {
        String incomingTxId = "incoming-123";
        // Updated: added 'null' as the first 'request' object argument
        TraceRuntime.onHttpInStart(null, "GET", "/api/test", incomingTxId, null, false);

        assertEquals(incomingTxId, TxIdHolder.get());
        assertNotNull(SpanIdHolder.get());
        assertEquals(1, capturedEvents.size());
        assertEquals(incomingTxId, capturedEvents.get(0).txId());
        assertEquals(TraceEventType.HTTP_IN_START, capturedEvents.get(0).type());
    }

    @Nested
    @DisplayName("safeKeyToString Tests")
    class SafeKeyToStringTests {
        @Test void testNull() { assertNull(TraceRuntime.safeKeyToString(null)); }
        @Test void testByteArray() {
            byte[] key = "user:1001".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("user:1001", TraceRuntime.safeKeyToString(key));
        }
        @Test void testString() { assertEquals("session:abc", TraceRuntime.safeKeyToString("session:abc")); }
        @Test void testInteger() { assertEquals("42", TraceRuntime.safeKeyToString(42)); }
    }

    @Nested
    @DisplayName("Span Hierarchy Tests")
    class SpanHierarchyTest {

        @Test
        @DisplayName("Root span should have null parentSpanId")
        void rootSpanParentIdIsNull() {
            TraceRuntime.onHttpInStart(null, "GET", "/api/test", "tx-001", null, false);
            assertFalse(capturedEvents.isEmpty());
            TraceEvent event = capturedEvents.get(0);
            assertNull(event.parentSpanId());
        }

        @Test
        @DisplayName("Child span should use root's spanId as parentSpanId")
        void childSpanParentIdMatchesRootSpanId() {
            TraceRuntime.onHttpInStart(null, "GET", "/api/test", "tx-001", null, false);
            String rootSpanId = SpanIdHolder.get();
            TraceRuntime.onDbQueryStart("SELECT 1", "localhost");
            TraceRuntime.onDbQueryEnd("SELECT 1", 5L, "localhost");
            TraceEvent dbEvent = capturedEvents.get(2);
            assertEquals(rootSpanId, dbEvent.parentSpanId());
        }

        @Test
        @DisplayName("HTTP_OUT should carry parentSpanId")
        void httpOutCarriesParentSpanId() {
            TraceRuntime.onHttpInStart(null, "GET", "/api/test", "tx-001", null, false);
            String rootSpanId = SpanIdHolder.get();
            TraceRuntime.onHttpOut("GET", "http://external/api", 200, 10L);
            TraceEvent outEvent = capturedEvents.get(1);
            assertEquals(rootSpanId, outEvent.parentSpanId());
        }

        @Test
        @DisplayName("Distributed trace: adopted parentSpanId")
        void distributedTraceAdoptsParentSpanId() {
            String upstreamSpanId = "parent-999";
            TraceRuntime.onHttpInStart(null, "GET", "/api/test", "tx-001", upstreamSpanId, false);
            TraceEvent event = capturedEvents.get(0);
            assertEquals(upstreamSpanId, event.parentSpanId());
        }

        @Test
        @DisplayName("Context clear on end")
        void contextClearedOnHttpEnd() {
            TraceRuntime.onHttpInStart(null, "GET", "/api/test", "tx-001", null, false);
            TraceRuntime.onHttpInEnd("GET", "/api/test", 200, 10L);
            assertNull(TxIdHolder.get());
        }

        @Test
        @DisplayName("Context clear on error")
        void contextClearedOnHttpError() {
            TraceRuntime.onHttpInStart(null, "GET", "/api/test", "tx-001", null, false);
            TraceRuntime.onHttpInError(new RuntimeException("fail"), "GET", "/api/test", 10L);
            assertNull(TxIdHolder.get());
        }

        @Test
        @DisplayName("Sequential transactions should have different spanIds")
        void sequentialTransactionsDifferentSpans() {
            TraceRuntime.onHttpInStart(null, "GET", "/api/test", "tx-001", null, false);
            String span1 = SpanIdHolder.get();
            TraceRuntime.onHttpInEnd("GET", "/api/test", 200, 10L);
            TraceRuntime.onHttpInStart(null, "POST", "/api/order", "tx-002", null, false);
            String span2 = SpanIdHolder.get();
            assertNotEquals(span1, span2);
        }
    }

    @Test
    @DisplayName("invokeGetAttribute/invokeSetAttribute should read and write attributes")
    void invokeAttributeHelpers_work() {
        AttributeCarrier c = new AttributeCarrier();

        TraceRuntime.invokeSetAttribute(c, "k", "v");
        Object out = TraceRuntime.invokeGetAttribute(c, "k");

        assertEquals("v", out);
    }

    @Test
    @DisplayName("invokeMethodSimple returns null when method missing")
    void invokeMethodSimple_missingMethod_returnsNull() {
        Object out = TraceRuntime.invokeMethodSimple(new Object(), "nope");
        assertNull(out);
    }

    @Test
    @DisplayName("findMethod finds inherited declared methods")
    void findMethod_findsInheritedMethod() {
        assertNotNull(TraceRuntime.findMethod(ChildCarrier.class, "greet"));
    }

    @Test
    @DisplayName("isSecondaryDispatch: marker=true이면 true")
    void isSecondaryDispatch_markerTrue() {
        AttributeCarrier c = new AttributeCarrier();
        c.setAttribute(TraceRuntime.TRACE_MARKER, Boolean.TRUE);
        assertTrue(TraceRuntime.isSecondaryDispatch(c));
    }

    @Test
    @DisplayName("isSecondaryDispatch: dispatcherType=ASYNC이면 true")
    void isSecondaryDispatch_dispatcherTypeAsync() {
        DispatchTypeCarrier c = new DispatchTypeCarrier("ASYNC");
        assertTrue(TraceRuntime.isSecondaryDispatch(c));
    }

    @Test
    @DisplayName("restoreContext restores tx/span from request attributes")
    void restoreContext_restoresFromAttributes() {
        AttributeCarrier c = new AttributeCarrier();
        c.setAttribute(TraceRuntime.ATTR_TX_ID, "tx-restore");
        c.setAttribute(TraceRuntime.ATTR_SPAN_ID, "span-restore");

        TraceRuntime.restoreContext(c);

        assertEquals("tx-restore", TxIdHolder.get());
        assertEquals("span-restore", SpanIdHolder.get());
    }

    @Test
    @DisplayName("truncate long string appends ellipsis")
    void truncate_longString() {
        String s = "x".repeat(1001);
        String out = TraceRuntime.truncate(s);
        assertTrue(out.endsWith("..."));
        assertEquals(1003, out.length());
    }

    @Test
    @DisplayName("emit should send child event when tx exists")
    void emit_sendsWhenTxExists() {
        TxIdHolder.set("tx-emit");
        SpanIdHolder.set("span-parent");
        Map<String, Object> extra = new HashMap<>();
        extra.put("k", "v");

        TraceRuntime.emit(TraceEventType.HTTP_OUT, TraceCategory.HTTP, "/emit", 3L, true, extra);

        assertEquals(1, capturedEvents.size());
        assertEquals("tx-emit", capturedEvents.get(0).txId());
        assertEquals("span-parent", capturedEvents.get(0).parentSpanId());
    }

    @Test
    @DisplayName("TraceRuntime ABI wrappers should be callable across categories")
    void abiWrappers_smokeCoverage() {
        TraceRuntime.onHttpInStart(null, "GET", "/abi", "tx-abi", null, true);
        TraceRuntime.onHttpOutError(new RuntimeException("out-err"), "GET", "http://localhost/abi", 2L);
        TraceRuntime.onHttpInEndAsync("tx-abi", "span-abi", "GET", "/abi", System.currentTimeMillis() - 1, null);

        TraceRuntime.onDbQueryError(new RuntimeException("db-err"), "select 1", 1L, "h2");

        TraceRuntime.onMqProduce("kafka", "t1", "k1");
        TraceRuntime.onMqConsumeStart("kafka", "t1", "tx-abi");
        TraceRuntime.onMqConsumeErrorMark(new IllegalStateException("mq-mark"));
        TraceRuntime.onMqConsumeError(new RuntimeException("mq-err"), "kafka", "t1", 1L);
        TraceRuntime.onMqConsumeComplete("kafka", "t1", 1L);

        TraceRuntime.onCacheError(new RuntimeException("cache-err"), "get", "k");
        TraceRuntime.attachCacheGetListener(new CompletableFuture<>(), "k");
        TraceRuntime.attachCacheOpListener(new CompletableFuture<>(), "set", "k");

        TraceRuntime.onFileReadError("/tmp/a", 1L, 1L, new RuntimeException("io-r"));
        TraceRuntime.onFileWriteError("/tmp/b", 1L, 1L, new RuntimeException("io-w"));

        Object sameMono = new Object();
        assertSame(sameMono, TraceRuntime.wrapWebClientExchange(sameMono, "GET", "http://localhost/x"));
        assertSame(sameMono, TraceRuntime.wrapWebFluxHandle(sameMono, new Object(), System.currentTimeMillis()));

        TraceRuntime.registerAsyncListenerFromRequest(new Object());
        TraceRuntime.onWebFluxHandleStart(new Object());
        TraceRuntime.onWebFluxHandleSyncError();

        // 최소 호출 보장: wrapper 호출로 인한 이벤트 전송이 일부 이상 발생해야 한다.
        assertFalse(capturedEvents.isEmpty());
    }

    static class AttributeCarrier {
        private final Map<String, Object> attrs = new HashMap<>();

        public Object getAttribute(String name) {
            return attrs.get(name);
        }

        public void setAttribute(String name, Object value) {
            attrs.put(name, value);
        }
    }

    static class DispatchTypeCarrier extends AttributeCarrier {
        private final String dispatch;

        DispatchTypeCarrier(String dispatch) {
            this.dispatch = dispatch;
        }

        public Object getDispatcherType() {
            return dispatch;
        }
    }

    static class ParentCarrier {
        @SuppressWarnings("unused")
        private String greet() {
            return "hello";
        }
    }

    static class ChildCarrier extends ParentCarrier {
    }
}
