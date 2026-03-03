package org.example.agent.plugin.executor;

import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.plugin.BaseAdvice;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

/**
 * Enhanced ExecutorPlugin to support ThreadPoolExecutor, ScheduledThreadPoolExecutor,
 * and ForkJoinPool (used by CompletableFuture and parallel streams).
 */
public class ExecutorPlugin implements TracerPlugin {

    @Override public String pluginId() { return "executor"; }
    @Override public boolean requiresBootstrapSearch() { return true; }

    @Override
    public List<String> targetClassPrefixes() {
        return AgentConfig.getPluginTargetPrefixes(pluginId(), Arrays.asList(
            "java/util/concurrent/ThreadPoolExecutor",
            "java/util/concurrent/ScheduledThreadPoolExecutor",
            "java/util/concurrent/ForkJoinPool"
        ));
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(new ExecutorTransformer());
    }

    static class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;
        SafeClassWriter(ClassReader cr, ClassLoader loader) {
            super(cr, ClassWriter.COMPUTE_FRAMES);
            this.loader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        }
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> d = Class.forName(type2.replace('/', '.'), false, loader);
                if (c.isAssignableFrom(d)) return type1;
                if (d.isAssignableFrom(c)) return type2;
                if (c.isInterface() || d.isInterface()) return "java/lang/Object";
                do { c = c.getSuperclass(); } while (!c.isAssignableFrom(d));
                return c.getName().replace('.', '/');
            } catch (Throwable e) { return "java/lang/Object"; }
        }
    }

    static class ExecutorTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] classfileBuffer) {
            if (className == null) return null;
            String normalized = className.replace('.', '/');
            
            boolean isTPE = "java/util/concurrent/ThreadPoolExecutor".equals(normalized) 
                         || "java/util/concurrent/ScheduledThreadPoolExecutor".equals(normalized);
            boolean isFJP = "java/util/concurrent/ForkJoinPool".equals(normalized);

            if (!isTPE && !isFJP) return null;

            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new SafeClassWriter(cr, loader);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        
                        // ThreadPoolExecutor / ScheduledThreadPoolExecutor
                        if (isTPE && "execute".equals(name) && "(Ljava/lang/Runnable;)V".equals(descriptor)) {
                            return new RunnableWrappingAdvice(mv, access, name, descriptor);
                        }
                        
                        // ForkJoinPool (used by CompletableFuture)
                        // In Java 17+, external tasks often go through externalPush or externalSubmit
                        if (isFJP && (name.startsWith("external") || "execute".equals(name))) {
                            if (descriptor.contains("Ljava/util/concurrent/ForkJoinTask;")) {
                                // Task is already a ForkJoinTask, which is harder to wrap without subclasses.
                                // For now, we focus on Runnable/Callable submissions.
                            } else if (descriptor.contains("Ljava/lang/Runnable;")) {
                                return new RunnableWrappingAdvice(mv, access, name, descriptor);
                            }
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    /**
     * Wraps the Runnable argument (slot 1) with ContextCapturingRunnable.
     */
    static class RunnableWrappingAdvice extends AdviceAdapter {
        protected RunnableWrappingAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodEnter() {
            mv.visitTypeInsn(NEW, "org/example/agent/core/ContextCapturingRunnable");
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, "org/example/agent/core/ContextCapturingRunnable", "<init>", "(Ljava/lang/Runnable;)V", false);
            mv.visitVarInsn(ASTORE, 1);
        }
    }
}
