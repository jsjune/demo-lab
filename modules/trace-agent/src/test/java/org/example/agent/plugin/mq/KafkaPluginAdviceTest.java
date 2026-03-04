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
    @DisplayName("MessagingMessageListenerAdapter.onMessage 진입 시 onMqConsumeStart가 호출되어야 한다")
    void testKafkaAdapterAdviceOnMethodEnter() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        // Descriptor must start with ConsumerRecord to match KafkaAdapterTransformer's filter
        String desc = "(Lorg/apache/kafka/clients/consumer/ConsumerRecord;Ljava/lang/Object;Ljava/lang/Object;)V";
        KafkaPlugin.KafkaAdapterAdvice advice = new KafkaPlugin.KafkaAdapterAdvice(
                mv, Opcodes.ACC_PUBLIC, "onMessage", desc);

        advice.onMethodEnter();

        // extractTxId → setTxIdIfPresent → extractTopic → TxIdHolder.get → onMqConsumeStart
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqConsumeStart"),
            anyString(),
            eq(false)
        );
    }

    @Test
    @DisplayName("MessagingMessageListenerAdapter.onMessage 정상 종료 시 onMqConsumeComplete가 호출되어야 한다")
    void testKafkaAdapterAdviceOnMethodExit_callsComplete() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        String desc = "(Lorg/apache/kafka/clients/consumer/ConsumerRecord;Ljava/lang/Object;Ljava/lang/Object;)V";
        KafkaPlugin.KafkaAdapterAdvice advice = new KafkaPlugin.KafkaAdapterAdvice(
            mv, Opcodes.ACC_PUBLIC, "onMessage", desc);

        advice.onMethodEnter();
        advice.onMethodExit(Opcodes.RETURN);

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqConsumeComplete"),
            anyString(),
            eq(false)
        );
    }

    @Test
    @DisplayName("handleException 진입 시 onMqConsumeErrorMark가 호출되어야 한다")
    void testKafkaHandleExceptionAdvice_marksError() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        String desc = "(Ljava/lang/Exception;Lorg/apache/kafka/clients/consumer/ConsumerRecord;)V";
        KafkaPlugin.KafkaHandleExceptionAdvice advice = new KafkaPlugin.KafkaHandleExceptionAdvice(
            mv, Opcodes.ACC_PROTECTED, "handleException", desc);

        advice.onMethodEnter();

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqConsumeErrorMark"),
            eq("(Ljava/lang/Throwable;)V"),
            eq(false)
        );
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqConsumeComplete"),
            eq("(Ljava/lang/String;Ljava/lang/String;J)V"),
            eq(false)
        );
    }

    @Test
    @DisplayName("3.3+ handleException 시그니처에서도 onMqConsumeErrorMark가 호출되어야 한다")
    void testKafkaHandleExceptionAdvice_v33Signature_marksError() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        String desc = "(Ljava/lang/Object;Lorg/springframework/kafka/support/Acknowledgment;Lorg/apache/kafka/clients/consumer/Consumer;Lorg/springframework/messaging/Message;Lorg/springframework/kafka/listener/ListenerExecutionFailedException;)V";
        KafkaPlugin.KafkaHandleExceptionAdvice advice = new KafkaPlugin.KafkaHandleExceptionAdvice(
            mv, Opcodes.ACC_PROTECTED, "handleException", desc);

        advice.onMethodEnter();

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqConsumeErrorMark"),
            eq("(Ljava/lang/Throwable;)V"),
            eq(false)
        );
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onMqConsumeComplete"),
            eq("(Ljava/lang/String;Ljava/lang/String;J)V"),
            eq(false)
        );
    }
}
