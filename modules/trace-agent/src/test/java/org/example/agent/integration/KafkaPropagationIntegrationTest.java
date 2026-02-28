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
    @DisplayName("Kafka 어댑터와 리스너가 협력하여 업스트림 txId를 유지해야 한다")
    void testKafkaPropagationAcrossAdapterAndListener() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        var adapterTransformer = plugin.transformers().get(2);
        var listenerTransformer = plugin.transformers().get(1);

        String upstreamId = "upstream-tx-999";

        // 1. Transform Adapter — class name must contain "MessagingMessageListenerAdapter"
        //    to pass KafkaAdapterTransformer's internal class-name guard.
        Class<?> transformedAdapter = transformAndLoad(DummyMessagingMessageListenerAdapter.class, adapterTransformer);
        // 2. Transform Listener
        Class<?> transformedListener = transformAndLoad(DummyListener.class, listenerTransformer);

        Object adapterInstance = transformedAdapter.getDeclaredConstructor().newInstance();
        Object listenerInstance = transformedListener.getDeclaredConstructor().newInstance();

        // Simulate Spring Kafka behavior: Adapter calls Listener
        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            try (MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
                pluginMock.when(() -> KafkaPlugin.extractTxId(any())).thenReturn(upstreamId);
                pluginMock.when(() -> KafkaPlugin.resolveTopicOrFallback(any(), anyString())).thenReturn("test-topic");

                // Step 1: Adapter.onMessage — KafkaAdapterAdvice extracts txId and sets TxIdHolder
                Method onMessage = transformedAdapter.getDeclaredMethod("onMessage", org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);
                onMessage.invoke(adapterInstance, null, null, null);

                // Step 2: Listener.handle — KafkaListenerAdvice reads TxIdHolder.get()
                Method handle = transformedListener.getDeclaredMethod("handle", String.class);
                handle.invoke(listenerInstance, "payload");

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

    public static class DummyListener {
        @org.springframework.kafka.annotation.KafkaListener(topics = "test")
        public void handle(String payload) {
            // Business logic
        }
    }
}
