package org.example.agent.plugin.cache;

import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.plugin.BaseAdvice;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

public class RedisPlugin implements TracerPlugin {

    @Override public String pluginId() { return "cache"; }

    @Override
    public List<String> targetClassPrefixes() {
        return AgentConfig.getPluginTargetPrefixes(pluginId(), Arrays.asList(
            "io/lettuce/core/",
            "redis/clients/jedis/"
        ));
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(new LettuceTransformer(), new JedisTransformer());
    }

    // -----------------------------------------------------------------------
    // Lettuce
    // -----------------------------------------------------------------------

    static class LettuceTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalized = className == null ? "" : className.replace('.', '/');
            if (!normalized.startsWith("io/lettuce/core/") || !normalized.contains("RedisAsyncCommands")) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (isTargetCommand(name) && descriptor.endsWith("Lio/lettuce/core/RedisFuture;")) {
                            return new LettuceAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }

        private boolean isTargetCommand(String name) {
            return Arrays.asList("get", "set", "del", "hget", "hset", "eval", "evalsha").contains(name);
        }
    }

    static class LettuceAdvice extends BaseAdvice {
        private final String commandName;
        private int keyLocalIdx; // get/hget 키 보관용 (onMethodExit에서 참조)

        protected LettuceAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.commandName = name;
        }

        @Override
        protected void onMethodEnter() {
            keyLocalIdx = newLocal(Type.getType(String.class));
            if ("eval".equals(commandName) || "evalsha".equals(commandName)) {
                mv.visitLdcInsn("lua:" + commandName);
            } else {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "safeKeyToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            }
            mv.visitVarInsn(ASTORE, keyLocalIdx);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) {
                mv.visitInsn(DUP);
                mv.visitLdcInsn(commandName);
                mv.visitVarInsn(ALOAD, keyLocalIdx);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheError",
                    "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;)V", false);
                return;
            }
            if (("get".equals(commandName) || "hget".equals(commandName)) && opcode == ARETURN) {
                mv.visitInsn(DUP);
                mv.visitVarInsn(ALOAD, keyLocalIdx);
                mv.visitMethodInsn(INVOKESTATIC,
                    "org/example/agent/core/TraceRuntime",
                    "attachCacheGetListener",
                    "(Ljava/lang/Object;Ljava/lang/String;)V", false);
                return;
            }
            if (opcode == ARETURN) {
                mv.visitInsn(DUP);
                mv.visitLdcInsn(commandName);
                mv.visitVarInsn(ALOAD, keyLocalIdx);
                mv.visitMethodInsn(INVOKESTATIC,
                    "org/example/agent/core/TraceRuntime",
                    "attachCacheOpListener",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Jedis
    // -----------------------------------------------------------------------

    static class JedisTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalized = className == null ? "" : className.replace('.', '/');
            if (!normalized.startsWith("redis/clients/jedis/") || !normalized.contains("Jedis")) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (isTargetCommand(name) && descriptor.startsWith("(Ljava/lang/String;")) {
                            return new JedisAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }

        private boolean isTargetCommand(String name) {
            return Arrays.asList("get", "set", "del", "eval", "evalsha").contains(name);
        }
    }

    static class JedisAdvice extends BaseAdvice {
        private final String commandName;
        private int keyLocalIdx; // get 키 보관용 (onMethodExit에서 참조)

        protected JedisAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.commandName = name;
        }

        @Override
        protected void onMethodEnter() {
            keyLocalIdx = newLocal(Type.getType(String.class));
            if ("eval".equals(commandName) || "evalsha".equals(commandName)) {
                mv.visitLdcInsn("lua:" + commandName);
            } else {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "safeKeyToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            }
            mv.visitVarInsn(ASTORE, keyLocalIdx);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) {
                mv.visitInsn(DUP);
                mv.visitLdcInsn(commandName);
                mv.visitVarInsn(ALOAD, keyLocalIdx);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheError",
                    "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;)V", false);
                return;
            }
            if ("get".equals(commandName) && opcode == ARETURN) {
                // Stack at ARETURN: [..., String(retVal)]
                mv.visitInsn(DUP);                           // [..., retVal, retVal(dup)]
                Label hitLabel  = new Label();
                Label afterLabel = new Label();
                mv.visitJumpInsn(IFNONNULL, hitLabel);        // dup 소비 → [..., retVal]; null→MISS
                // MISS path
                mv.visitVarInsn(ALOAD, keyLocalIdx);          // [..., retVal, key]
                mv.visitInsn(ICONST_0);                      // [..., retVal, key, false]
                mv.visitMethodInsn(INVOKESTATIC,
                    "org/example/agent/core/TraceRuntime",
                    "onCacheGet", "(Ljava/lang/String;Z)V", false);
                mv.visitJumpInsn(GOTO, afterLabel);           // → [..., retVal]
                // HIT path
                mv.visitLabel(hitLabel);                     // Stack: [..., retVal]
                mv.visitVarInsn(ALOAD, keyLocalIdx);          // [..., retVal, key]
                mv.visitInsn(ICONST_1);                      // [..., retVal, key, true]
                mv.visitMethodInsn(INVOKESTATIC,
                    "org/example/agent/core/TraceRuntime",
                    "onCacheGet", "(Ljava/lang/String;Z)V", false);
                mv.visitLabel(afterLabel);
                // Stack: [..., retVal] — ARETURN이 retVal 반환
                return;
            }
            if (opcode == ARETURN) {
                mv.visitVarInsn(ALOAD, keyLocalIdx);
                if ("del".equals(commandName)) {
                    mv.visitMethodInsn(INVOKESTATIC,
                        "org/example/agent/core/TraceRuntime",
                        "onCacheDel", "(Ljava/lang/String;)V", false);
                } else {
                    mv.visitMethodInsn(INVOKESTATIC,
                        "org/example/agent/core/TraceRuntime",
                        "onCacheSet", "(Ljava/lang/String;)V", false);
                }
            }
        }
    }
}
