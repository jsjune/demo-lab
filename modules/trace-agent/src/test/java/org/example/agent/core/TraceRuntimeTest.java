package org.example.agent.core;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

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
}
