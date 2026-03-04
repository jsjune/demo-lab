package org.example.agent.integration;

import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.mq.KafkaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("통합: Kafka 전파(Propagation) 검증")
class KafkaPropagationIntegrationTest extends ByteBuddyIntegrationTest {

    @Test
    @DisplayName("Kafka 어댑터가 ConsumerRecord 헤더에서 업스트림 txId를 추출하여 onMqConsumeStart에 전달해야 한다")
    void testKafkaPropagationThroughAdapter() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        // transformers(): [0] KafkaProducerTransformer, [1] KafkaAdapterTransformer
        var adapterTransformer = plugin.transformers().get(1);

        String upstreamId = "upstream-tx-999";

        // Transform Adapter — class name must contain "MessagingMessageListenerAdapter"
        // to pass KafkaAdapterTransformer's internal class-name guard.
        Class<?> transformedAdapter = transformAndLoad(DummyMessagingMessageListenerAdapter.class, adapterTransformer);
        Object adapterInstance = transformedAdapter.getDeclaredConstructor().newInstance();

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            try (MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
                pluginMock.when(() -> KafkaPlugin.extractTxId(any())).thenReturn(upstreamId);
                pluginMock.when(() -> KafkaPlugin.extractTopic(any())).thenReturn("test-topic");

                // Adapter.onMessage — KafkaAdapterAdvice extracts txId, sets TxIdHolder, calls onMqConsumeStart
                Method onMessage = transformedAdapter.getDeclaredMethod("onMessage",
                        org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);
                onMessage.invoke(adapterInstance, null, null, null);

                // Verify that TraceRuntime.onMqConsumeStart was called with the upstream txId
                runtimeMock.verify(() -> TraceRuntime.onMqConsumeStart(anyString(), anyString(), eq(upstreamId)), atLeastOnce());
            }
        }
    }

    // Class name must contain "MessagingMessageListenerAdapter" to pass KafkaAdapterTransformer's guard.
    public static class DummyMessagingMessageListenerAdapter {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
            // Simulates RecordMessagingMessageListenerAdapter.onMessage()
        }
    }

    @Test
    @DisplayName("어댑터가 예외를 내부 handleException으로 소비해도 error mark + consume complete가 호출되어야 한다")
    void testKafkaAdapterInternalHandleExceptionPath_marksErrorAndCompletes() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        var adapterTransformer = plugin.transformers().get(1);

        Class<?> transformedAdapter = transformAndLoad(DummyMessagingMessageListenerAdapterWithHandleException.class, adapterTransformer);
        Object adapterInstance = transformedAdapter.getDeclaredConstructor().newInstance();

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            try (MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
                pluginMock.when(() -> KafkaPlugin.extractTxId(any())).thenReturn("tx-kafka-err-1");
                pluginMock.when(() -> KafkaPlugin.extractTopic(any())).thenReturn("test-topic");

                Method onMessage = transformedAdapter.getDeclaredMethod("onMessage",
                    org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);
                onMessage.invoke(adapterInstance, null, null, null);

                runtimeMock.verify(() -> TraceRuntime.onMqConsumeErrorMark(any(Throwable.class)), atLeastOnce());
                runtimeMock.verify(() -> TraceRuntime.onMqConsumeComplete(anyString(), anyString(), anyLong()), atLeastOnce());
            }
        }
    }

    public static class DummyMessagingMessageListenerAdapterWithHandleException {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
            try {
                throw new RuntimeException("listener boom");
            } catch (Exception e) {
                handleException(e, record);
            }
        }

        protected void handleException(Exception ex, org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record) {
            // no-op
        }
    }

    @Test
    @DisplayName("3.3+ handleException(ListenerExecutionFailedException) 경로에서도 error mark + complete가 호출되어야 한다")
    void testKafkaAdapterV33HandleExceptionPath_marksErrorAndCompletes() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        var adapterTransformer = plugin.transformers().get(1);

        Class<?> transformedAdapter = transformAndLoad(DummyMessagingMessageListenerAdapterWithV33HandleException.class, adapterTransformer);
        Object adapterInstance = transformedAdapter.getDeclaredConstructor().newInstance();

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            try (MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
                pluginMock.when(() -> KafkaPlugin.extractTxId(any())).thenReturn("tx-kafka-err-33");
                pluginMock.when(() -> KafkaPlugin.extractTopic(any())).thenReturn("test-topic");

                Method onMessage = transformedAdapter.getDeclaredMethod("onMessage",
                    org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);
                onMessage.invoke(adapterInstance, null, null, null);

                runtimeMock.verify(() -> TraceRuntime.onMqConsumeErrorMark(any(Throwable.class)), atLeastOnce());
                runtimeMock.verify(() -> TraceRuntime.onMqConsumeComplete(anyString(), anyString(), anyLong()), atLeastOnce());
            }
        }
    }

    public static class DummyMessagingMessageListenerAdapterWithV33HandleException {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
            org.springframework.kafka.listener.ListenerExecutionFailedException ex =
                new org.springframework.kafka.listener.ListenerExecutionFailedException("listener boom", new RuntimeException("listener boom"));
            handleException(record, null, null, null, ex);
        }

        protected void handleException(Object rec, org.springframework.kafka.support.Acknowledgment ack,
                                       org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                                       org.springframework.messaging.Message<?> message,
                                       org.springframework.kafka.listener.ListenerExecutionFailedException ex) {
            // no-op
        }
    }
}
