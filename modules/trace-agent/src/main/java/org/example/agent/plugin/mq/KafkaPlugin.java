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
            // Package-level prefix so both MessagingMessageListenerAdapter (abstract) and
            // RecordMessagingMessageListenerAdapter (concrete, overrides onMessage) are
            // explicit matches in CompositeTransformer → bypass BASE_IGNORE_PACKAGES.
            "org/springframework/kafka/listener/adapter/",
            "" // For @KafkaListener scan
        );
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(
            new KafkaProducerTransformer(),
            new KafkaListenerTransformer(),
            new KafkaAdapterTransformer()
        );
    }

    // ... (existing transformers)

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
                        // RecordMessagingMessageListenerAdapter.onMessage(ConsumerRecord, ...)
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
        protected KafkaAdapterAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            // ALOAD 1 is ConsumerRecord
            // Use setTxIdIfPresent to avoid TxIdHolder.set(null) which would erase any
            // existing txId and prevent KafkaListenerAdvice from falling back to it.
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin", "extractTxId", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin", "setTxIdIfPresent", "(Ljava/lang/String;)V", false);
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

    static class KafkaListenerTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            // Pre-scan: check for @KafkaListener before creating ClassWriter.
            // ClassWriter.COMPUTE_FRAMES calls getCommonSuperClass() via Class.forName() during
            // reader.accept(). For abstract classes (e.g. org.antlr.v4.runtime.atn.PredictionContext)
            // that are currently being loaded, this causes a re-entrant defineClass() call →
            // LinkageError: duplicate abstract class definition on JDK 17+.
            // By returning null early for classes without @KafkaListener, we avoid creating
            // ClassWriter entirely, so getCommonSuperClass() is never triggered for them.
            if (!hasKafkaListenerAnnotation(classfileBuffer)) return null;
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
                final boolean[] found = {false};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        return new KafkaListenerAdvice(mv, access, name, descriptor, found);
                    }
                }, ClassReader.EXPAND_FRAMES);
                return found[0] ? writer.toByteArray() : null;
            } catch (Exception e) { return null; }
        }

        private static boolean hasKafkaListenerAnnotation(byte[] classfileBuffer) {
            final boolean[] found = {false};
            try {
                new ClassReader(classfileBuffer).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                     String signature, String[] exceptions) {
                        if (found[0]) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String annotDesc, boolean visible) {
                                if (annotDesc.contains("KafkaListener")) found[0] = true;
                                return null;
                            }
                        };
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Exception ignored) {}
            return found[0];
        }
    }

    static class KafkaListenerAdvice extends BaseAdvice {
        private final boolean[] classModified;
        private final String descriptor;
        private boolean isTarget = false;
        private boolean ignored = false;
        private String annotationTopic = "unknown-topic";
        private int topicLocalId = -1;

        protected KafkaListenerAdvice(MethodVisitor mv, int access, String name, String descriptor, boolean[] classModified) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.classModified = classModified;
            this.descriptor = descriptor;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.contains("TraceIgnore")) {
                ignored = true;
            }
            if (descriptor.contains("KafkaListener")) {
                isTarget = true;
                classModified[0] = true;
                return new AnnotationVisitor(Opcodes.ASM9, super.visitAnnotation(descriptor, visible)) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("topics".equals(name) && value instanceof String) {
                            annotationTopic = (String) value;
                        }
                        super.visit(name, value);
                    }
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if ("topics".equals(name)) {
                            return new AnnotationVisitor(Opcodes.ASM9, super.visitArray(name)) {
                                @Override
                                public void visit(String name, Object value) {
                                    if (value instanceof String) {
                                        annotationTopic = (String) value;
                                    }
                                    super.visit(name, value);
                                }
                            };
                        }
                        return super.visitArray(name);
                    }
                };
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        protected void onMethodEnter() {
            if (!isTarget || ignored) return;
            captureStartTime();

            // Find the index of the ConsumerRecord argument
            int recordArgIdx = findRecordArgumentIndex();
            
            topicLocalId = newLocal(Type.getType(String.class));
            mv.visitVarInsn(ALOAD, recordArgIdx);
            mv.visitLdcInsn(annotationTopic);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/mq/KafkaPlugin",
                "resolveTopicOrFallback", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, topicLocalId);

            // KafkaAdapterAdvice already set TxIdHolder from the real ConsumerRecord headers
            // before this @KafkaListener method is called.  Use TxIdHolder.get() directly
            // instead of extractTxId(record), because the record argument here may be a
            // String/POJO (not a ConsumerRecord) when the listener method is typed that way.
            mv.visitLdcInsn("kafka");
            mv.visitVarInsn(ALOAD, topicLocalId);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder",
                "get", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime",
                "onMqConsumeStart", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
        }

        private int findRecordArgumentIndex() {
            Type[] args = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < args.length; i++) {
                if (args[i].getSort() == Type.OBJECT && args[i].getInternalName().contains("ConsumerRecord")) {
                    return i + 1; // 0 is 'this'
                }
            }
            return 1; // Fallback to first arg
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (!isTarget || ignored) return;
            if (opcode == ATHROW) {
                // Stack: [..., throwable] — DUP so the original throwable survives for re-throw
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

    // -----------------------------------------------------------------------
    // Helpers called from injected bytecode
    // -----------------------------------------------------------------------

    /** Returns the record's runtime topic if available, otherwise the annotation fallback. */
    public static String resolveTopicOrFallback(Object record, String fallback) {
        String topic = extractTopic(record);
        return topic != null ? topic : fallback;
    }

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
