package org.example.agent.integration;

import org.example.agent.core.TraceIgnore;
import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.mq.KafkaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.mockito.Mockito.*;

@DisplayName("통합: @TraceIgnore 지원 및 KafkaAdapterTransformer 필터 검증")
class TraceIgnoreIntegrationTest extends ByteBuddyIntegrationTest {

    @Test
    @DisplayName("onMessage(String) 시그니처는 KafkaAdapterTransformer 대상이 아니므로 인스트루멘테이션이 적용되지 않아야 한다")
    void testNonConsumerRecordMethod_notInstrumented() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        // transformers(): [0] KafkaProducerTransformer, [1] KafkaAdapterTransformer
        var adapterTransformer = plugin.transformers().get(1);
        String className = NormalKafkaListener.class.getName().replace('.', '/');
        byte[] original = loadClassBytes(NormalKafkaListener.class);
        byte[] transformed = adapterTransformer.transform(
            NormalKafkaListener.class.getClassLoader(), className, NormalKafkaListener.class, null, original);

        // 필터 미매칭이면 transformer는 null을 반환해야 한다.
        org.junit.jupiter.api.Assertions.assertNull(transformed);
    }

    @Test
    @DisplayName("MessagingMessageListenerAdapter.onMessage(ConsumerRecord, ...) 호출 시 인스트루멘테이션이 적용되어야 한다")
    void testAdapterOnMessage_isInstrumented() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        var adapterTransformer = plugin.transformers().get(1);

        // 클래스 이름에 "MessagingMessageListenerAdapter" 포함 + ConsumerRecord 시그니처 — 필터 통과
        Class<?> transformedAdapter = transformAndLoad(DummyMessagingMessageListenerAdapter.class, adapterTransformer);
        Object instance = transformedAdapter.getDeclaredConstructor().newInstance();
        Method onMessage = transformedAdapter.getDeclaredMethod("onMessage",
                org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            try (MockedStatic<KafkaPlugin> pluginMock = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
                pluginMock.when(() -> KafkaPlugin.extractTxId(any())).thenReturn(null);
                pluginMock.when(() -> KafkaPlugin.extractTopic(any())).thenReturn("test-topic");

                onMessage.invoke(instance, null, null, null);

                // txId는 extractTxId(null) → null → TxIdHolder 미설정 → TxIdHolder.get() == null
                // any()로 null 포함 매칭
                runtimeMock.verify(() -> TraceRuntime.onMqConsumeStart(any(), any(), any()), atLeastOnce());
            }
        }
    }

    // Adapter 클래스: 클래스명에 "MessagingMessageListenerAdapter" 포함 필수
    public static class DummyMessagingMessageListenerAdapter {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
        }
    }

    // onMessage(String) 시그니처 — ConsumerRecord 아니므로 KafkaAdapterTransformer 대상 아님
    public static class NormalKafkaListener {
        @org.springframework.kafka.annotation.KafkaListener(topics = "test")
        public void onMessage(String data) {
        }
    }

    // @TraceIgnore 어노테이션이 붙은 리스너 (참고용 — kafka-plugin-refactor 후 어댑터 레벨에서 추적하므로
    // @KafkaListener 메서드 레벨 @TraceIgnore는 더 이상 직접 지원되지 않음)
    public static class IgnoredKafkaListener {
        @TraceIgnore
        @org.springframework.kafka.annotation.KafkaListener(topics = "test")
        public void onMessage(String data) {
        }
    }

    private byte[] loadClassBytes(Class<?> clazz) throws Exception {
        String path = clazz.getName().replace('.', '/') + ".class";
        try (java.io.InputStream in = clazz.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("class bytes not found: " + path);
            return in.readAllBytes();
        }
    }
}
