package org.example.agent.plugin.mq;

import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.TxIdHolder;
import org.example.agent.plugin.BaseAdvice;
import org.example.agent.plugin.ReflectionUtils;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

public class KafkaPlugin implements TracerPlugin {

    @Override public String pluginId() { return "mq"; }

    @Override
    public List<String> targetClassPrefixes() {
        return Arrays.asList(
                "org/apache/kafka/clients/producer/KafkaProducer",
                "org/apache/kafka/clients/consumer/KafkaConsumer",
                "org/springframework/kafka/core/KafkaTemplate",
                "org/springframework/kafka/listener/"
        );
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(
            new KafkaProducerTransformer(),
            new KafkaAdapterTransformer()
        );
    }

    static class KafkaAdapterTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!className.replace('.', '/').contains("MessagingMessageListenerAdapter")) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                // Same getCommonSuperClass() safety as KafkaProducerTransformer.
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        try { return super.getCommonSuperClass(type1, type2); }
                        catch (Throwable t) { return "java/lang/Object"; }
                    }
                };
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        // Instrument onMessage(ConsumerRecord, ...) — handles all @KafkaListener dispatch
                        if ("onMessage".equals(name) && descriptor.startsWith("(Lorg/apache/kafka/clients/consumer/ConsumerRecord;")) {
                            return new KafkaAdapterAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class KafkaAdapterAdvice extends BaseAdvice {
        private int topicLocalId = -1;

        protected KafkaAdapterAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            captureStartTime();

            // Extract TxId from ConsumerRecord headers → TxIdHolder
            // Use setTxIdIfPresent to avoid erasing an existing txId with null.
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin",
                "extractTxId", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin",
                "setTxIdIfPresent", "(Ljava/lang/String;)V", false);

            // Store topic in a local variable for reuse in onMethodExit
            topicLocalId = newLocal(Type.getType(String.class));
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin",
                "extractTopic", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, topicLocalId);

            // onMqConsumeStart("kafka", topic, txId)
            mv.visitLdcInsn("kafka");
            mv.visitVarInsn(ALOAD, topicLocalId);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder",
                "get", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime",
                "onMqConsumeStart", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) {
                // Stack: [..., t] — DUP so the original throwable survives re-throw
                mv.visitInsn(DUP);
                mv.visitLdcInsn("kafka");
                mv.visitVarInsn(ALOAD, topicLocalId);
                calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime",
                    "onMqConsumeError", "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V", false);
                return;
            }
            mv.visitLdcInsn("kafka");
            mv.visitVarInsn(ALOAD, topicLocalId);
            calculateDurationAndPush();
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime",
                "onMqConsumeEnd", "(Ljava/lang/String;Ljava/lang/String;J)V", false);
        }
    }

    static class KafkaProducerTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"org/apache/kafka/clients/producer/KafkaProducer".equals(className.replace('.', '/'))) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                // COMPUTE_FRAMES triggers ClassWriter.getCommonSuperClass() → Class.forName() for every
                // referenced type in KafkaProducer. Kafka internal classes may not yet be loaded at
                // transform time, causing ClassNotFoundException → exception → transform returns null
                // (KafkaProducer left uninstrumented → header never injected).
                // Override getCommonSuperClass() to fall back to java/lang/Object on any failure.
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        try { return super.getCommonSuperClass(type1, type2); }
                        catch (Throwable t) { return "java/lang/Object"; }
                    }
                };
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("send".equals(name) && descriptor.startsWith("(Lorg/apache/kafka/clients/producer/ProducerRecord;")) {
                            return new KafkaProducerAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class KafkaProducerAdvice extends BaseAdvice {
        protected KafkaProducerAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder", "get", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin", "injectHeader", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
            mv.visitLdcInsn("kafka");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin", "extractTopic", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/apache/kafka/clients/producer/ProducerRecord", "key", "()Ljava/lang/Object;", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onMqProduce", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers called from injected bytecode
    // -----------------------------------------------------------------------

    /** Sets TxIdHolder only when txId is non-null/non-empty to avoid erasing an existing txId. */
    public static void setTxIdIfPresent(String txId) {
        if (txId != null && !txId.isEmpty()) {
            TxIdHolder.set(txId);
        }
    }

    public static void injectHeader(Object record, String txId) {
        if (txId == null || record == null) return;
        AgentLogger.debug("[MQ-PRODUCE] Injecting txId into record headers: " + txId);
        boolean[] injected = {false};
        ReflectionUtils.invokeMethod(record, "headers").ifPresent(headers -> {
            ReflectionUtils.invokeMethod(headers, "add", AgentConfig.getHeaderKey(), txId.getBytes(StandardCharsets.UTF_8));
            injected[0] = true;
        });
        if (!injected[0]) {
            AgentLogger.debug("[MQ-PRODUCE] Failed to inject txId header — record.headers() returned empty");
        }
    }

    public static String extractTxId(Object record) {
        if (record == null) return null;
        String txId = ReflectionUtils.invokeMethod(record, "headers")
            .flatMap(h -> ReflectionUtils.invokeMethod(h, "lastHeader", AgentConfig.getHeaderKey()))
            .flatMap(header -> ReflectionUtils.invokeMethod(header, "value"))
            .map(val -> new String((byte[]) val, StandardCharsets.UTF_8))
            .orElse(null);
        AgentLogger.debug("[MQ-CONSUME] extractTxId from record(" + record.getClass().getSimpleName() + "): " + txId);
        return txId;
    }

    public static String extractTopic(Object record) {
        return ReflectionUtils.invokeMethod(record, "topic")
            .map(Object::toString)
            .orElse(null);
    }
}
