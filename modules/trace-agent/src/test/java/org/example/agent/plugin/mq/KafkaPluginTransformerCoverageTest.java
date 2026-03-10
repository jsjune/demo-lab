package org.example.agent.plugin.mq;

import org.example.agent.instrumentation.ByteBuddyIntegrationTest;
import org.example.agent.core.TraceRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaPluginTransformerCoverageTest extends ByteBuddyIntegrationTest {

    @Test
    void pluginMetadata() {
        KafkaPlugin p = new KafkaPlugin();
        assertEquals("mq", p.pluginId());
    }

    @Test
    void kafkaAdapterAdvice_isAppliedToOnMessageWithConsumerRecord() throws Exception {
        Class<?> cls = instrument(
            DummyAdapter.class,
            KafkaPlugin.KafkaAdapterAdvice.class,
            named("onMessage").and(
                takesArgument(0, named("org.apache.kafka.clients.consumer.ConsumerRecord"))));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method onMessage = cls.getDeclaredMethod("onMessage",
            org.apache.kafka.clients.consumer.ConsumerRecord.class, Object.class, Object.class);

        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
             MockedStatic<KafkaPlugin> kp = mockStatic(KafkaPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            kp.when(() -> KafkaPlugin.extractTxId(nullable(Object.class))).thenReturn(null);
            kp.when(() -> KafkaPlugin.extractTopic(nullable(Object.class))).thenReturn("test-topic");

            onMessage.invoke(instance, null, null, null);

            rt.verify(() -> TraceRuntime.onMqConsumeStart(anyString(), anyString(), nullable(String.class)), atLeastOnce());
        }
    }

    public static class DummyAdapter {
        public void onMessage(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Object ack, Object consumer) {
        }
    }
}
