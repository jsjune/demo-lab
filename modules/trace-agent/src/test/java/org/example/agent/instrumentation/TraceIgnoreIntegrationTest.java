package org.example.agent.instrumentation;

import org.example.agent.core.TraceIgnore;
import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.mq.KafkaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("통합: @TraceIgnore 및 Kafka 메서드 시그니처 필터링 검증")
class TraceIgnoreIntegrationTest extends ByteBuddyIntegrationTest {

    @Test
    @DisplayName("onMessage(String) 시그니처는 ConsumerRecord 매처에 매칭되지 않아야 한다")
    void testNonConsumerRecordMethod_notInstrumented() throws Exception {
        // Apply KafkaAdapterAdvice with ConsumerRecord argument matcher
        // NormalKafkaListener.onMessage(String) does not match → no instrumentation applied
        Class<?> cls = instrument(
            NormalKafkaListener.class,
            KafkaPlugin.KafkaAdapterAdvice.class,
            named("onMessage").and(
                takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerRecord"))));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method onMessage = cls.getDeclaredMethod("onMessage", String.class);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            onMessage.invoke(instance, "data");

            // Since the method didn't match the advice, TraceRuntime should not be called
            runtimeMock.verify(() -> TraceRuntime.onMqConsumeStart(anyString(), anyString(), anyString()), never());
        }
    }

    @Test
    @DisplayName("MessagingMessageListenerAdapter.onMessage(ConsumerRecord, ...) 호출 시 인스트루멘테이션이 적용되어야 한다")
    void testAdapterOnMessage_isInstrumented() throws Exception {
        Class<?> cls = instrument(
            DummyMessagingMessageListenerAdapter.class,
            KafkaPlugin.KafkaAdapterAdvice.class,
            named("onMessage").and(
                takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerRecord"))));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method onMessage = cls.getDeclaredMethod("onMessage",
            org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class);
             MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            pluginMock.when(() -> KafkaPlugin.extractTxId(nullable(Object.class))).thenReturn(null);
            pluginMock.when(() -> KafkaPlugin.extractTopic(nullable(Object.class))).thenReturn("test-topic");

            onMessage.invoke(instance, null, null, null);

            runtimeMock.verify(() -> TraceRuntime.onMqConsumeStart(anyString(), anyString(), nullable(String.class)), atLeastOnce());
        }
    }

    public static class DummyMessagingMessageListenerAdapter {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
        }
    }

    public static class NormalKafkaListener {
        @org.springframework.kafka.annotation.KafkaListener(topics = "test")
        public void onMessage(String data) {
        }
    }

    public static class IgnoredKafkaListener {
        @TraceIgnore
        @org.springframework.kafka.annotation.KafkaListener(topics = "test")
        public void onMessage(String data) {
        }
    }
}
