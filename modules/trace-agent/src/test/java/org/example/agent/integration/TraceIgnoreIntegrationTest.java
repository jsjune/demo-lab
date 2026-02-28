package org.example.agent.integration;

import org.example.agent.core.TraceIgnore;
import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.mq.KafkaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.mockito.Mockito.*;

@DisplayName("통합: @TraceIgnore 지원 검증")
class TraceIgnoreIntegrationTest extends ByteBuddyIntegrationTest {

    @Test
    @DisplayName("@TraceIgnore 어노테이션이 붙은 메서드는 Kafka 인스트루멘테이션을 건너뛰어야 한다")
    void testTraceIgnoreOnKafkaListener() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        // The transformer for listeners
        var transformer = plugin.transformers().get(1);

        Class<?> transformedClass = transformAndLoad(IgnoredKafkaListener.class, transformer);
        Object instance = transformedClass.getDeclaredConstructor().newInstance();
        Method method = transformedClass.getDeclaredMethod("onMessage", String.class);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance, "test-payload");

            // Verify that onMqConsumeStart was NOT called
            runtimeMock.verify(() -> TraceRuntime.onMqConsumeStart(anyString(), anyString(), anyString()), never());
        }
    }

    @Test
    @DisplayName("@TraceIgnore 어노테이션이 없는 메서드는 정상적으로 인스트루멘테이션되어야 한다")
    void testNormalKafkaListener() throws Exception {
        KafkaPlugin plugin = new KafkaPlugin();
        var transformer = plugin.transformers().get(1);

        Class<?> transformedClass = transformAndLoad(NormalKafkaListener.class, transformer);
        Object instance = transformedClass.getDeclaredConstructor().newInstance();
        Method method = transformedClass.getDeclaredMethod("onMessage", String.class);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance, "test-payload");

            // Verify that onMqConsumeStart WAS called
            runtimeMock.verify(() -> TraceRuntime.onMqConsumeStart(any(), any(), any()), atLeastOnce());
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
            // Business logic
        }
    }
}
