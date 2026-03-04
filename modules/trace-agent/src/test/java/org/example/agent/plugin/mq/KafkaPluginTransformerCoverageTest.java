package org.example.agent.plugin.mq;

import org.example.agent.testutil.AsmTestUtils;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class KafkaPluginTransformerCoverageTest {

    @Test
    void kafkaProducerTransformer_transformsSendMethod() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "org/apache/kafka/clients/producer/KafkaProducer",
            AsmTestUtils.MethodSpec.of("send",
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;)Ljava/util/concurrent/Future;"));

        KafkaPlugin.KafkaProducerTransformer t = new KafkaPlugin.KafkaProducerTransformer();
        byte[] out = t.transform(getClass().getClassLoader(),
            "org/apache/kafka/clients/producer/KafkaProducer", null, null, original);

        assertNotNull(out);
    }

    @Test
    void kafkaProducerTransformer_commonSuperFallback_pathReturnsBytes() throws Exception {
        byte[] original = kafkaProducerWithUnknownMergeTypes();

        KafkaPlugin.KafkaProducerTransformer t = new KafkaPlugin.KafkaProducerTransformer();
        byte[] out = t.transform(getClass().getClassLoader(),
            "org/apache/kafka/clients/producer/KafkaProducer", null, null, original);

        assertNotNull(out);
    }

    @Test
    void kafkaProducerTransformer_nonTarget_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods("com/example/NoKafka");
        KafkaPlugin.KafkaProducerTransformer t = new KafkaPlugin.KafkaProducerTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "com/example/NoKafka", null, null, original));
    }

    @Test
    void kafkaAdapterTransformer_transformsOnMessageAndHandleException() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "org/springframework/kafka/listener/MessagingMessageListenerAdapter",
            AsmTestUtils.MethodSpec.of("onMessage",
                "(Lorg/apache/kafka/clients/consumer/ConsumerRecord;Ljava/lang/Object;Ljava/lang/Object;)V"),
            AsmTestUtils.MethodSpec.of("handleException", "(Ljava/lang/Throwable;)V"));

        KafkaPlugin.KafkaAdapterTransformer t = new KafkaPlugin.KafkaAdapterTransformer();
        byte[] out = t.transform(getClass().getClassLoader(),
            "org/springframework/kafka/listener/MessagingMessageListenerAdapter", null, null, original);

        assertNotNull(out);
    }

    @Test
    void kafkaAdapterTransformer_nonTarget_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods("com/example/NoAdapter");
        KafkaPlugin.KafkaAdapterTransformer t = new KafkaPlugin.KafkaAdapterTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "com/example/NoAdapter", null, null, original));
    }

    private static byte[] kafkaProducerWithUnknownMergeTypes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
            "org/apache/kafka/clients/producer/KafkaProducer", null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor send = cw.visitMethod(Opcodes.ACC_PUBLIC, "send",
            "(Lorg/apache/kafka/clients/producer/ProducerRecord;)Ljava/util/concurrent/Future;",
            null, null);
        send.visitCode();
        send.visitInsn(Opcodes.ACONST_NULL);
        send.visitInsn(Opcodes.ARETURN);
        send.visitMaxs(0, 0);
        send.visitEnd();

        MethodVisitor merge = cw.visitMethod(Opcodes.ACC_PUBLIC, "mergeTypes", "()Ljava/lang/Object;", null, null);
        merge.visitCode();
        Label elseLabel = new Label();
        Label endLabel = new Label();
        merge.visitInsn(Opcodes.ICONST_0);
        merge.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        merge.visitTypeInsn(Opcodes.NEW, "x/UnknownLeft");
        merge.visitInsn(Opcodes.DUP);
        merge.visitMethodInsn(Opcodes.INVOKESPECIAL, "x/UnknownLeft", "<init>", "()V", false);
        merge.visitJumpInsn(Opcodes.GOTO, endLabel);
        merge.visitLabel(elseLabel);
        merge.visitTypeInsn(Opcodes.NEW, "y/UnknownRight");
        merge.visitInsn(Opcodes.DUP);
        merge.visitMethodInsn(Opcodes.INVOKESPECIAL, "y/UnknownRight", "<init>", "()V", false);
        merge.visitLabel(endLabel);
        merge.visitInsn(Opcodes.ARETURN);
        merge.visitMaxs(0, 0);
        merge.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}

