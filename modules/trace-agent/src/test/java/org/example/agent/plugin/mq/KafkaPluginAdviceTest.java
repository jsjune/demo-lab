package org.example.agent.plugin.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@DisplayName("플러그인: Kafka (Advice 바이트코드 검증)")
class KafkaPluginAdviceTest {

    @Test
    @DisplayName("KafkaProducer.send 호출 시 헤더 주입 및 Produce 이벤트 발행이 호출되어야 한다")
    void testKafkaProducerAdviceOnMethodEnter() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        KafkaPlugin.KafkaProducerAdvice advice = new KafkaPlugin.KafkaProducerAdvice(mv, Opcodes.ACC_PUBLIC, "send", "(Lorg/apache/kafka/clients/producer/ProducerRecord;)Ljava/util/concurrent/Future;");

        advice.onMethodEnter();

        // Verify that it injects the header and emits a trace event
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/plugin/mq/KafkaPlugin"),
            eq("injectHeader"),
            anyString(),
            eq(false)
        );

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqProduce"),
            anyString(),
            eq(false)
        );
    }

    @Test
    @DisplayName("@KafkaListener 메서드 진입 시 onMqConsumeStart가 호출되어야 한다")
    void testKafkaListenerAdviceOnMethodEnter() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        boolean[] classModified = {false};
        // Signature with ConsumerRecord at index 1
        String desc = "(Lorg/apache/kafka/clients/consumer/ConsumerRecord;)V";
        KafkaPlugin.KafkaListenerAdvice advice = new KafkaPlugin.KafkaListenerAdvice(mv, Opcodes.ACC_PUBLIC, "onMessage", desc, classModified);
        
        // Mocking the annotation visit
        advice.visitAnnotation("Lorg/springframework/kafka/annotation/KafkaListener;", true);

        advice.onMethodEnter();

        // Verify that it calls TraceRuntime.onMqConsumeStart
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqConsumeStart"),
            anyString(),
            eq(false)
        );
    }
}
