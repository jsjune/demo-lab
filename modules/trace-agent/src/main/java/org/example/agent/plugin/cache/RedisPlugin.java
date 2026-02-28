package org.example.agent.plugin.cache;

import org.example.agent.TracerPlugin;
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
        return Arrays.asList(
            "io/lettuce/core/AbstractRedisAsyncCommands",
            "redis/clients/jedis/Jedis"
        );
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
            if (!"io/lettuce/core/AbstractRedisAsyncCommands".equals(className.replace('.', '/'))) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (isTargetCommand(name)) return new LettuceAdvice(mv, access, name, descriptor);
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }

        private boolean isTargetCommand(String name) {
            return Arrays.asList("get", "set", "del", "hget", "hset").contains(name);
        }
    }

    static class LettuceAdvice extends BaseAdvice {
        private final String commandName;

        protected LettuceAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.commandName = name;
        }

        @Override
        protected void onMethodEnter() {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "safeKeyToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);

            if ("get".equals(commandName) || "hget".equals(commandName)) {
                mv.visitInsn(ICONST_1);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheGet", "(Ljava/lang/String;Z)V", false);
            } else if ("set".equals(commandName) || "hset".equals(commandName)) {
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheSet", "(Ljava/lang/String;)V", false);
            } else if ("del".equals(commandName)) {
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheDel", "(Ljava/lang/String;)V", false);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Jedis
    // -----------------------------------------------------------------------

    static class JedisTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"redis/clients/jedis/Jedis".equals(className.replace('.', '/'))) return null;
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
            return Arrays.asList("get", "set", "del").contains(name);
        }
    }

    static class JedisAdvice extends BaseAdvice {
        private final String commandName;

        protected JedisAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.commandName = name;
        }

        @Override
        protected void onMethodEnter() {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "safeKeyToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);

            if ("get".equals(commandName)) {
                mv.visitInsn(ICONST_1);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheGet", "(Ljava/lang/String;Z)V", false);
            } else if ("set".equals(commandName)) {
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheSet", "(Ljava/lang/String;)V", false);
            } else if ("del".equals(commandName)) {
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onCacheDel", "(Ljava/lang/String;)V", false);
            }
        }
    }
}
