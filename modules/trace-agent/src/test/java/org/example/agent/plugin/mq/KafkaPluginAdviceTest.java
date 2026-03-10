package org.example.agent.plugin.mq;

import org.example.agent.core.TraceRuntime;
import org.example.agent.core.context.TxIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("플러그인: Kafka (@Advice 메서드 검증)")
class KafkaPluginAdviceTest {

    @BeforeEach
    void setUp() { TxIdHolder.clear(); }

    @AfterEach
    void tearDown() { TxIdHolder.clear(); }

    // -----------------------------------------------------------------------
    // KafkaPollAdvice (pure KafkaConsumer.poll() support)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("KafkaPollAdvice 테스트")
    class KafkaPollAdviceTest {

        @Test
        @DisplayName("exit: records > 0 이면 onMqConsumeStart + onMqConsumeComplete 호출")
        void pollExit_withRecords_callsConsumeEvents() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
                 MockedStatic<KafkaPlugin> kp = mockStatic(KafkaPlugin.class,
                         withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

                kp.when(() -> KafkaPlugin.getRecordsCount(any())).thenReturn(3);
                kp.when(() -> KafkaPlugin.getFirstTopic(any())).thenReturn("raw-topic");

                KafkaPlugin.KafkaPollAdvice.exit(new Object(), null, 100L);

                rt.verify(() -> TraceRuntime.onMqConsumeStart(eq("kafka"), eq("raw-topic"), any()), times(1));
                rt.verify(() -> TraceRuntime.onMqConsumeComplete(eq("kafka"), eq("raw-topic"), anyLong()), times(1));
            }
        }

        @Test
        @DisplayName("exit: records == 0 이면 이벤트 미발생")
        void pollExit_emptyRecords_noEvents() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
                 MockedStatic<KafkaPlugin> kp = mockStatic(KafkaPlugin.class,
                         withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

                kp.when(() -> KafkaPlugin.getRecordsCount(any())).thenReturn(0);

                KafkaPlugin.KafkaPollAdvice.exit(new Object(), null, 100L);

                rt.verify(() -> TraceRuntime.onMqConsumeStart(any(), any(), any()), never());
                rt.verify(() -> TraceRuntime.onMqConsumeComplete(any(), any(), anyLong()), never());
            }
        }

        @Test
        @DisplayName("exit: thrown != null 이면 이벤트 미발생")
        void pollExit_thrown_noEvents() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                KafkaPlugin.KafkaPollAdvice.exit(null, new RuntimeException("poll err"), 100L);
                rt.verify(() -> TraceRuntime.onMqConsumeStart(any(), any(), any()), never());
            }
        }
    }

    @Test
    @DisplayName("KafkaProducer.send enter: injectHeader와 onMqProduce가 호출되어야 한다")
    void producerEnter_callsInjectHeaderAndOnMqProduce() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
             MockedStatic<KafkaPlugin> kp = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            Object fakeRecord = new Object();
            kp.when(() -> KafkaPlugin.extractTopic(any())).thenReturn("my-topic");
            kp.when(() -> KafkaPlugin.extractKey(any())).thenReturn("key-1");

            KafkaPlugin.KafkaProducerEnterAdvice.enter(fakeRecord);

            kp.verify(() -> KafkaPlugin.injectHeader(eq(fakeRecord), any()), times(1));
            rt.verify(() -> TraceRuntime.onMqProduce(eq("kafka"), eq("my-topic"), eq("key-1")), times(1));
        }
    }

    @Test
    @DisplayName("KafkaAdapter.onMessage enter: txId 추출 및 onMqConsumeStart 호출")
    void adapterEnter_callsOnMqConsumeStart() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
             MockedStatic<KafkaPlugin> kp = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            Object fakeRecord = new Object();
            kp.when(() -> KafkaPlugin.extractTxId(any())).thenReturn("upstream-tx-1");
            kp.when(() -> KafkaPlugin.extractTopic(any())).thenReturn("consume-topic");

            KafkaPlugin.KafkaAdapterAdvice.enter(fakeRecord, null, 0L);

            rt.verify(() -> TraceRuntime.onMqConsumeStart(eq("kafka"), eq("consume-topic"), any()), times(1));
        }
    }

    @Test
    @DisplayName("KafkaAdapter.onMessage exit: 정상 종료 → onMqConsumeComplete 호출")
    void adapterExit_normal_callsOnMqConsumeComplete() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            KafkaPlugin.KafkaAdapterAdvice.exit(null, "topic-1", System.currentTimeMillis() - 100);
            rt.verify(() -> TraceRuntime.onMqConsumeComplete(eq("kafka"), eq("topic-1"), anyLong()), times(1));
            rt.verify(() -> TraceRuntime.onMqConsumeError(any(), any(), any(), anyLong()), never());
        }
    }

    @Test
    @DisplayName("KafkaAdapter.onMessage exit: 예외 → onMqConsumeError 호출")
    void adapterExit_thrown_callsOnMqConsumeError() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            Throwable err = new RuntimeException("consumer boom");
            KafkaPlugin.KafkaAdapterAdvice.exit(err, "topic-2", System.currentTimeMillis() - 50);
            rt.verify(() -> TraceRuntime.onMqConsumeError(eq(err), eq("kafka"), eq("topic-2"), anyLong()), times(1));
            rt.verify(() -> TraceRuntime.onMqConsumeComplete(any(), any(), anyLong()), never());
        }
    }

    @Test
    @DisplayName("KafkaHandleExceptionEnterAdvice: onMqConsumeErrorMark와 onMqConsumeComplete 호출")
    void handleExceptionEnter_callsErrorMarkAndComplete() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            Throwable err = new RuntimeException("handled");
            KafkaPlugin.KafkaHandleExceptionEnterAdvice.enter(err);
            rt.verify(() -> TraceRuntime.onMqConsumeErrorMark(eq(err)), times(1));
            rt.verify(() -> TraceRuntime.onMqConsumeComplete(eq("kafka"), isNull(), eq(-1L)), times(1));
        }
    }
}
