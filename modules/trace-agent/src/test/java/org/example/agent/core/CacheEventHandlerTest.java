package org.example.agent.core;

import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: CacheEventHandler 단위 테스트")
class CacheEventHandlerTest {

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
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @Test
    @DisplayName("T-01: onGet(hit=true) — CACHE_HIT 이벤트 전송")
    void onGet_hit_emitsCacheHitEvent() {
        CacheEventHandler.onGet("user:1", true);

        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.CACHE_HIT, capturedEvents.get(0).type());
        assertEquals("user:1", capturedEvents.get(0).extraInfo().get("key"));
    }

    @Test
    @DisplayName("T-02: onGet(hit=false) — CACHE_MISS 이벤트 전송")
    void onGet_miss_emitsCacheMissEvent() {
        CacheEventHandler.onGet("user:2", false);

        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.CACHE_MISS, capturedEvents.get(0).type());
    }

    @Test
    @DisplayName("T-03: onSet — CACHE_SET 이벤트 전송")
    void onSet_emitsCacheSetEvent() {
        CacheEventHandler.onSet("session:abc");

        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.CACHE_SET, capturedEvents.get(0).type());
        assertEquals("session:abc", capturedEvents.get(0).extraInfo().get("key"));
    }

    @Test
    @DisplayName("T-04: onDel — CACHE_DEL 이벤트 전송")
    void onDel_emitsCacheDelEvent() {
        CacheEventHandler.onDel("expired:key");

        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.CACHE_DEL, capturedEvents.get(0).type());
    }
}
