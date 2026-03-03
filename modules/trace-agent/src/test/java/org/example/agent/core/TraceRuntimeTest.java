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
        // Signature updated: (method, path, incomingTxId, incomingSpanId, forceTrace)
        TraceRuntime.onHttpInStart("GET", "/api/test", incomingTxId, null, false);

        assertEquals(incomingTxId, TxIdHolder.get());
        assertNotNull(SpanIdHolder.get());
        assertEquals(1, capturedEvents.size());
        assertEquals(incomingTxId, capturedEvents.get(0).txId());
        assertEquals(TraceEventType.HTTP_IN_START, capturedEvents.get(0).type());
    }

    @Nested
    @DisplayName("safeKeyToString Tests")
    class SafeKeyToStringTests {
        @Test
        void testNull() {
            assertNull(TraceRuntime.safeKeyToString(null));
        }

        @Test
        void testByteArray() {
            byte[] key = "user:1001".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("user:1001", TraceRuntime.safeKeyToString(key));
        }

        @Test
        void testEmptyByteArray() {
            assertEquals("", TraceRuntime.safeKeyToString(new byte[0]));
        }

        @Test
        void testString() {
            assertEquals("session:abc", TraceRuntime.safeKeyToString("session:abc"));
        }

        @Test
        void testInteger() {
            assertEquals("42", TraceRuntime.safeKeyToString(42));
        }
    }

    @Nested
    @DisplayName("Span Hierarchy Tests")
    class SpanHierarchyTest {

        @Test
        @DisplayName("Root span should have null parentSpanId")
        void rootSpanParentIdIsNull() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", null, false);

            assertFalse(capturedEvents.isEmpty());
            TraceEvent event = capturedEvents.get(0);
            assertNull(event.parentSpanId(), "Root span's parentSpanId must be null");
            assertEquals(SpanIdHolder.get(), event.spanId());
        }

        @Test
        @DisplayName("Child span should use root's spanId as parentSpanId")
        void childSpanParentIdMatchesRootSpanId() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", null, false);
            String rootSpanId = SpanIdHolder.get();

            TraceRuntime.onDbQueryStart("SELECT 1", "localhost");
            TraceRuntime.onDbQueryEnd("SELECT 1", 5L, "localhost");

            assertEquals(3, capturedEvents.size()); // HTTP_IN_START, DB_QUERY_START, DB_QUERY_END
            TraceEvent dbEvent = capturedEvents.get(2);
            assertEquals(rootSpanId, dbEvent.parentSpanId(), "Child span's parentSpanId must match root spanId");
            assertNotEquals(rootSpanId, dbEvent.spanId());
        }

        @Test
        @DisplayName("HTTP_OUT should carry parentSpanId")
        void httpOutCarriesParentSpanId() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", null, false);
            String rootSpanId = SpanIdHolder.get();

            TraceRuntime.onHttpOut("GET", "http://external/api", 200, 10L);

            assertEquals(2, capturedEvents.size());
            TraceEvent outEvent = capturedEvents.get(1);
            assertEquals(rootSpanId, outEvent.parentSpanId());
        }

        @Test
        @DisplayName("Distributed trace: adopted parentSpanId")
        void distributedTraceAdoptsParentSpanId() {
            String upstreamSpanId = "parent-999";
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", upstreamSpanId, false);

            assertEquals(1, capturedEvents.size());
            TraceEvent event = capturedEvents.get(0);
            assertEquals(upstreamSpanId, event.parentSpanId(), "Root span should adopt upstream X-Span-Id as parent");
        }

        @Test
        @DisplayName("Context clear on end")
        void contextClearedOnHttpEnd() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", null, false);
            TraceRuntime.onHttpInEnd("GET", "/api/test", 200, 10L);

            assertNull(TxIdHolder.get());
            assertNull(SpanIdHolder.get());
        }

        @Test
        @DisplayName("Context clear on error")
        void contextClearedOnHttpError() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", null, false);
            TraceRuntime.onHttpInError(new RuntimeException("fail"), "GET", "/api/test", 10L);

            assertNull(TxIdHolder.get());
            assertNull(SpanIdHolder.get());
        }

        @Test
        @DisplayName("Orphan child event has null parentSpanId")
        void orphanEventParentIdIsNull() {
            // No active transaction (no onHttpInStart)
            TxIdHolder.set("orphan-tx");
            TraceRuntime.onDbQueryEnd("SELECT 1", 5L, "localhost");

            assertFalse(capturedEvents.isEmpty());
            TraceEvent dbEvent = capturedEvents.get(0);
            assertNull(dbEvent.parentSpanId(), "Orphan event's parentSpanId must be null");
        }

        @Test
        @DisplayName("Sequential transactions should have different spanIds")
        void sequentialTransactionsDifferentSpans() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", null, false);
            String span1 = SpanIdHolder.get();
            TraceRuntime.onHttpInEnd("GET", "/api/test", 200, 10L);

            TraceRuntime.onHttpInStart("POST", "/api/order", "tx-002", null, false);
            String span2 = SpanIdHolder.get();
            TraceRuntime.onHttpInEnd("POST", "/api/order", 201, 10L);

            assertNotEquals(span1, span2);
        }
    }
}
