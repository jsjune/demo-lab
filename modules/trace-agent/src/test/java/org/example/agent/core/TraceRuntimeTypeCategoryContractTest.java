package org.example.agent.core;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class TraceRuntimeTypeCategoryContractTest {
    private MockedStatic<TcpSender> tcpMock;
    private List<TraceEvent> captured;

    @BeforeEach
    void setUp() {
        captured = new ArrayList<>();
        tcpMock = mockStatic(TcpSender.class);
        tcpMock.when(() -> TcpSender.send(any(TraceEvent.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
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
    void httpTypeAndCategoryContract() {
        TraceRuntime.onHttpInStart(null, "GET", "/contract/http", "tx-http-1", null, false);
        TraceRuntime.onHttpOut("GET", "http://127.0.0.1/downstream", 200, 3L);
        TraceRuntime.onHttpInEnd("GET", "/contract/http", 200, 5L);

        assertEvent(TraceEventType.HTTP_IN_START, TraceCategory.HTTP);
        assertEvent(TraceEventType.HTTP_OUT, TraceCategory.HTTP);
        assertEvent(TraceEventType.HTTP_IN_END, TraceCategory.HTTP);
    }

    @Test
    void mqTypeAndCategoryContract() {
        TxIdHolder.set("tx-mq-1");
        SpanIdHolder.set("span-mq-parent");
        TraceRuntime.onMqProduce("kafka", "topic-contract", "k1");

        TraceRuntime.onMqConsumeStart("kafka", "topic-contract", "tx-mq-consume");
        TraceRuntime.onMqConsumeEnd("kafka", "topic-contract", 7L);

        assertEvent(TraceEventType.MQ_PRODUCE, TraceCategory.MQ);
        assertEvent(TraceEventType.MQ_CONSUME_START, TraceCategory.MQ);
        assertEvent(TraceEventType.MQ_CONSUME_END, TraceCategory.MQ);
    }

    @Test
    void dbTypeAndCategoryContract() {
        TraceRuntime.onHttpInStart(null, "GET", "/contract/db", "tx-db-1", null, false);
        TraceRuntime.onDbQueryStart("select 1", "h2:mem:test");
        TraceRuntime.onDbQueryEnd("select 1", 2L, "h2:mem:test");
        TraceRuntime.onHttpInEnd("GET", "/contract/db", 200, 9L);

        assertEvent(TraceEventType.DB_QUERY, TraceCategory.DB);
    }

    @Test
    void cacheTypeAndCategoryContract() {
        TraceRuntime.onHttpInStart(null, "GET", "/contract/cache", "tx-cache-1", null, false);
        TraceRuntime.onCacheGet("k", true);
        TraceRuntime.onCacheGet("k", false);
        TraceRuntime.onCacheSet("k");
        TraceRuntime.onCacheDel("k");
        TraceRuntime.onHttpInEnd("GET", "/contract/cache", 200, 10L);

        assertEvent(TraceEventType.CACHE_HIT, TraceCategory.CACHE);
        assertEvent(TraceEventType.CACHE_MISS, TraceCategory.CACHE);
        assertEvent(TraceEventType.CACHE_SET, TraceCategory.CACHE);
        assertEvent(TraceEventType.CACHE_DEL, TraceCategory.CACHE);
    }

    @Test
    void asyncTypeAndCategoryContract() {
        TxIdHolder.set("tx-async-1");
        SpanIdHolder.set("span-parent-1");

        String asyncSpan = TraceRuntime.onAsyncStart("task-contract");
        assertNotNull(asyncSpan);
        TraceRuntime.onAsyncEnd("task-contract", asyncSpan, 4L);

        assertEvent(TraceEventType.ASYNC_START, TraceCategory.ASYNC);
        assertEvent(TraceEventType.ASYNC_END, TraceCategory.ASYNC);
    }

    private void assertEvent(TraceEventType type, TraceCategory category) {
        boolean found = captured.stream().anyMatch(e -> e.type() == type && e.category() == category);
        assertTrue(found, "Expected event not found: " + type + " / " + category);
    }
}
