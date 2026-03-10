package org.example.agent.core;

import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.context.TxIdHolder;
import org.example.agent.core.emitter.TcpSender;
import org.example.agent.core.emitter.TcpSenderEmitter;
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
            TraceEvent dbEvent = capturedEvents.stream()
                .filter(e -> e.type() == TraceEventType.DB_QUERY)
                .findFirst()
                .orElseThrow();
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

    // --- fixtures for interface hierarchy tests ---
    interface WorkableBase { default void doWork() {} }
    interface WorkableExtended extends WorkableBase {}
    abstract static class AbstractWorker implements WorkableBase {}
    static class ConcreteWorker extends AbstractWorker {}
    static class ConcreteWorkerExtended implements WorkableExtended {}

    interface Level3 { default void deepMethod() {} }
    interface Level2 extends Level3 {}
    interface Level1 extends Level2 {}
    abstract static class AbstractLevel1Impl implements Level1 {}
    static class DeepConcreteChild extends AbstractLevel1Impl {}

    @Nested
    @DisplayName("findMethod 인터페이스 계층 탐색 테스트")
    class FindMethodInterfaceTest {

        @Test
        @DisplayName("findMethod는 슈퍼클래스가 구현한 인터페이스의 메서드를 찾는다")
        void findMethod_findsMethodInSuperclassInterface() {
            // ConcreteWorker extends AbstractWorker which implements WorkableBase
            // ConcreteWorker.getInterfaces() = [], current code misses it
            assertNotNull(TraceRuntime.findMethod(ConcreteWorker.class, "doWork"));
        }

        @Test
        @DisplayName("findMethod는 슈퍼클래스 인터페이스 깊이 2 이상도 탐색한다 (depth>=2, cycle-safe)")
        void findMethod_findsDeepInterfaceMethodViaSuperclass() {
            // DeepConcreteChild extends AbstractLevel1Impl implements Level1 extends Level2 extends Level3
            assertNotNull(TraceRuntime.findMethod(DeepConcreteChild.class, "deepMethod"));
        }
    }

    @Nested
    @DisplayName("EventEmitter 관리 테스트")
    class EventEmitterManagementTest {

        private List<TraceEvent> captured;

        @BeforeEach
        void setUp() {
            captured = new ArrayList<>();
            TraceRuntime.setEmitter(event -> captured.add(event));
        }

        @AfterEach
        void restoreEmitter() {
            TraceRuntime.setEmitter(new TcpSenderEmitter());
        }

        @Test
        @DisplayName("setEmitter로 주입한 emitter가 emitEvent()에서 사용된다")
        void setEmitter_usedByEmitEvent() {
            TraceEvent event = mock(TraceEvent.class);
            TraceRuntime.emitEvent(event);
            assertEquals(1, captured.size());
            assertSame(event, captured.get(0));
        }

        @Test
        @DisplayName("TraceRuntime.emit()은 setEmitter로 주입한 emitter를 통해 이벤트를 전송한다")
        void emit_usesInjectedEmitter() {
            TxIdHolder.set("tx-emitter");
            SpanIdHolder.set("span-emitter");
            TraceRuntime.emit(TraceEventType.HTTP_OUT, TraceCategory.HTTP, "/test", 5L, true, null);
            assertEquals(1, captured.size());
            assertEquals("tx-emitter", captured.get(0).txId());
        }
    }

    /**
     * Design-decision contract: emitEvent is a pure pass-through.
     * Central Latching in TraceRuntime is intentionally NOT applied because:
     *  - DB_QUERY, HTTP_OUT, and MQ_PRODUCE are validly emitted multiple times per request
     *  - Handler-level guards (HTTP_IN_FLIGHT_TX, DB_CALL_DEPTH, CONSUME_FINISHED) own deduplication
     */
    @Nested
    @DisplayName("emitEvent 패스스루 계약 (중앙 Latching 없음)")
    class EmitEventPassThroughContractTest {

        private final List<TraceEvent> captured = new ArrayList<>();

        @BeforeEach
        void setUpEmitter() {
            TraceRuntime.setEmitter(e -> captured.add(e));
        }

        @AfterEach
        void restoreEmitter() {
            TraceRuntime.setEmitter(new TcpSenderEmitter());
        }

        @Test
        @DisplayName("CT-01: 동일 타입 이벤트를 2회 emitEvent → 2건 모두 emitter에 전달 (중앙 필터 없음)")
        void emitEvent_sameTypeTwice_bothForwarded() {
            TraceEvent e1 = TraceRuntime.buildEvent("tx-1", TraceEventType.DB_QUERY, TraceCategory.DB,
                    "db-host", 10L, true, null, "span-1", null);
            TraceEvent e2 = TraceRuntime.buildEvent("tx-1", TraceEventType.DB_QUERY, TraceCategory.DB,
                    "db-host", 20L, true, null, "span-2", null);

            TraceRuntime.emitEvent(e1);
            TraceRuntime.emitEvent(e2);

            assertEquals(2, captured.size(), "emitEvent must forward all events; dedup is handler responsibility");
            assertEquals(10L, captured.get(0).durationMs());
            assertEquals(20L, captured.get(1).durationMs());
        }

        @Test
        @DisplayName("CT-02: 서로 다른 타입 이벤트 → 각 1건씩 전달")
        void emitEvent_differentTypes_allForwarded() {
            TraceEvent http = TraceRuntime.buildEvent("tx-2", TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
                    "/api", null, true, null, "span-a", null);
            TraceEvent db = TraceRuntime.buildEvent("tx-2", TraceEventType.DB_QUERY, TraceCategory.DB,
                    "db-host", 5L, true, null, "span-b", "span-a");

            TraceRuntime.emitEvent(http);
            TraceRuntime.emitEvent(db);

            assertEquals(2, captured.size());
            assertEquals(TraceEventType.HTTP_IN_START, captured.get(0).type());
            assertEquals(TraceEventType.DB_QUERY, captured.get(1).type());
        }
    }
}
