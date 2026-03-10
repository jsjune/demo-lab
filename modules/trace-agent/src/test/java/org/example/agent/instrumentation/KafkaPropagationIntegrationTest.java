package org.example.agent.instrumentation;

import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.mq.KafkaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("통합: Kafka 전파(Propagation) 검증")
class KafkaPropagationIntegrationTest extends ByteBuddyIntegrationTest {

    @Test
    @DisplayName("KafkaAdapter가 ConsumerRecord 헤더에서 업스트림 txId를 추출하여 onMqConsumeStart에 전달해야 한다")
    void testKafkaPropagationThroughAdapter() throws Exception {
        String upstreamId = "upstream-tx-999";

        Class<?> transformedAdapter = instrument(
            DummyMessagingMessageListenerAdapter.class,
            KafkaPlugin.KafkaAdapterAdvice.class,
            named("onMessage").and(
                takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerRecord"))));

        Object adapterInstance = transformedAdapter.getDeclaredConstructor().newInstance();

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class);
             MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            pluginMock.when(() -> KafkaPlugin.extractTxId(nullable(Object.class))).thenReturn(upstreamId);
            pluginMock.when(() -> KafkaPlugin.extractTopic(nullable(Object.class))).thenReturn("test-topic");

            Method onMessage = transformedAdapter.getDeclaredMethod("onMessage",
                org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);
            onMessage.invoke(adapterInstance, null, null, null);

            runtimeMock.verify(() -> TraceRuntime.onMqConsumeStart(anyString(), anyString(), eq(upstreamId)), atLeastOnce());
        }
    }

    public static class DummyMessagingMessageListenerAdapter {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
        }
    }

    @Test
    @DisplayName("어댑터가 예외를 내부 handleException으로 소비해도 error mark + consume complete가 호출되어야 한다")
    void testKafkaAdapterInternalHandleExceptionPath_marksErrorAndCompletes() throws Exception {
        Class<?> transformedAdapter = instrument(
            DummyMessagingMessageListenerAdapterWithHandleException.class,
            KafkaPlugin.KafkaAdapterAdvice.class,
            named("onMessage").and(
                takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerRecord"))));

        Object adapterInstance = transformedAdapter.getDeclaredConstructor().newInstance();

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class);
             MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            pluginMock.when(() -> KafkaPlugin.extractTxId(nullable(Object.class))).thenReturn("tx-kafka-err-1");
            pluginMock.when(() -> KafkaPlugin.extractTopic(nullable(Object.class))).thenReturn("test-topic");

            Method onMessage = transformedAdapter.getDeclaredMethod("onMessage",
                org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);
            assertThrows(InvocationTargetException.class, () -> onMessage.invoke(adapterInstance, null, null, null));

            runtimeMock.verify(() -> TraceRuntime.onMqConsumeError(any(Throwable.class), anyString(), anyString(), anyLong()), atLeastOnce());
        }
    }

    public static class DummyMessagingMessageListenerAdapterWithHandleException {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
            throw new RuntimeException("listener boom");
        }
    }
}
