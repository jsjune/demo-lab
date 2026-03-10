package org.example.agent.core.handler;

import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.emitter.TcpSender;
import org.example.agent.core.context.TxIdHolder;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: MqEventHandler 단위 테스트")
class MqEventHandlerTest {

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
        TxIdHolder.clear();
        SpanIdHolder.clear();
        MqEventHandler.resetForTest();
    }

    @AfterEach
    void tearDown() {
        tcpMock.close();
        TxIdHolder.clear();
        SpanIdHolder.clear();
        MqEventHandler.resetForTest();
    }

    @Test
    @DisplayName("T-01: onConsumeStart — incomingTxId 채택")
    void onConsumeStart_adoptIncomingTxId() {
        MqEventHandler.onConsumeStart("kafka", "my-topic", "tx-incoming");

        assertEquals("tx-incoming", TxIdHolder.get());
        assertNotNull(SpanIdHolder.get());
        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.MQ_CONSUME_START, capturedEvents.get(0).type());
        assertEquals("tx-incoming", capturedEvents.get(0).txId());
    }

    @Test
    @DisplayName("T-02: onConsumeStart — incomingTxId null이면 신규 txId 생성")
    void onConsumeStart_noIncomingTxId_generatesTxId() {
        MqEventHandler.onConsumeStart("kafka", "my-topic", null);

        assertNotNull(TxIdHolder.get());
        assertNotNull(SpanIdHolder.get());
        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.MQ_CONSUME_START, capturedEvents.get(0).type());
    }

    @Test
    @DisplayName("T-03: onConsumeEnd — MQ_CONSUME_END 이벤트, 컨텍스트 클리어")
    void onConsumeEnd_emitsEventAndClearsContext() {
        MqEventHandler.onConsumeStart("kafka", "my-topic", "tx-001");
        capturedEvents.clear();

        MqEventHandler.onConsumeEnd("kafka", "my-topic", 100L);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.MQ_CONSUME_END, e.type());
        assertEquals(100L, e.durationMs());
        assertTrue(e.success());
        assertNull(TxIdHolder.get());
        assertNull(SpanIdHolder.get());
    }

    @Test
    @DisplayName("T-04: onConsumeError — success=false")
    void onConsumeError_emitsSuccessFalse() {
        MqEventHandler.onConsumeStart("kafka", "my-topic", "tx-001");
        capturedEvents.clear();

        MqEventHandler.onConsumeError(new RuntimeException("err"), "kafka", "my-topic", 50L);

        assertEquals(1, capturedEvents.size());
        assertFalse(capturedEvents.get(0).success());
        assertEquals("RuntimeException", capturedEvents.get(0).extraInfo().get("errorType"));
        assertEquals("err", capturedEvents.get(0).extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-04-1: markConsumeError 후 onConsumeComplete — 실패 이벤트로 기록")
    void onConsumeComplete_withMarkedError_emitsFailure() {
        TxIdHolder.set("tx-001");
        SpanIdHolder.set("span-001");
        MqEventHandler.onConsumeStart("kafka", "my-topic", "tx-001");
        capturedEvents.clear();

        MqEventHandler.markConsumeError(new IllegalStateException("listener failed"));
        MqEventHandler.onConsumeComplete("kafka", null, -1L);

        assertEquals(1, capturedEvents.size());
        TraceEvent e = capturedEvents.get(0);
        assertEquals(TraceEventType.MQ_CONSUME_END, e.type());
        assertFalse(e.success());
        assertEquals("IllegalStateException", e.extraInfo().get("errorType"));
        assertEquals("listener failed", e.extraInfo().get("errorMessage"));
    }

    @Test
    @DisplayName("T-04-2: 완료 후 재완료 호출은 중복 이벤트를 만들지 않아야 한다")
    void onConsumeComplete_afterFinished_noDuplicate() {
        MqEventHandler.onConsumeStart("kafka", "my-topic", "tx-001");
        capturedEvents.clear();
        MqEventHandler.markConsumeError(new RuntimeException("x"));

        MqEventHandler.onConsumeComplete("kafka", "my-topic", -1L);
        MqEventHandler.onConsumeComplete("kafka", "my-topic", -1L);

        assertEquals(1, capturedEvents.size());
        assertEquals(TraceEventType.MQ_CONSUME_END, capturedEvents.get(0).type());
    }

    @Test
    @DisplayName("T-04-3: onConsumeError 후 onConsumeComplete — 중복 이벤트 없음")
    void onConsumeError_thenComplete_noDuplicate() {
        MqEventHandler.onConsumeStart("kafka", "my-topic", "tx-001");
        capturedEvents.clear();

        MqEventHandler.onConsumeError(new RuntimeException("err"), "kafka", "my-topic", 50L);
        MqEventHandler.onConsumeComplete("kafka", "my-topic", 99L);

        assertEquals(1, capturedEvents.size(), "onConsumeComplete after onConsumeError must not emit a second MQ_CONSUME_END");
        assertEquals(TraceEventType.MQ_CONSUME_END, capturedEvents.get(0).type());
        assertFalse(capturedEvents.get(0).success());
    }

    @Test
    @DisplayName("T-04-4: onConsumeEnd 후 onConsumeError — 중복 이벤트 없음")
    void onConsumeEnd_thenError_noDuplicate() {
        MqEventHandler.onConsumeStart("kafka", "my-topic", "tx-001");
        capturedEvents.clear();

        MqEventHandler.onConsumeEnd("kafka", "my-topic", 100L);
        MqEventHandler.onConsumeError(new RuntimeException("late-err"), "kafka", "my-topic", 200L);

        assertEquals(1, capturedEvents.size(), "onConsumeError after onConsumeEnd must not emit a second MQ_CONSUME_END");
        assertEquals(TraceEventType.MQ_CONSUME_END, capturedEvents.get(0).type());
        assertTrue(capturedEvents.get(0).success());
    }

    @Test
    @DisplayName("T-05: onProduce — txId null이면 이벤트 미전송")
    void onProduce_txIdNull_noEventEmitted() {
        MqEventHandler.onProduce("kafka", "topic", "key");
        assertTrue(capturedEvents.isEmpty());
    }
}
