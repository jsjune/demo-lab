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
}
