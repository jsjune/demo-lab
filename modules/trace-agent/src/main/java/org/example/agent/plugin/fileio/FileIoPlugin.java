package org.example.agent.plugin.fileio;

import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.plugin.BaseAdvice;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

/**
 * Instruments {@code FileInputStream.read(byte[],int,int)} and
 * {@code FileOutputStream.write(byte[],int,int)} to publish FILE_READ / FILE_WRITE events.
 *
 * <p>Disabled by default ({@code plugin.file-io.enabled=false}).
 * Requires Bootstrap ClassLoader registration because {@code java.io} classes are
 * loaded by the Bootstrap ClassLoader.
 */
public class FileIoPlugin implements TracerPlugin {

    @Override
    public String pluginId() { return "file-io"; }

    /** Instruments bootstrap-loaded java.io classes — agent jar must be on bootstrap search path. */
    @Override
    public boolean requiresBootstrapSearch() { return true; }

    /** Load after other plugins so bootstrap registration is deferred. */
    @Override
    public int order() { return 200; }

    @Override
    public boolean isEnabled(AgentConfig config) {
        return Boolean.parseBoolean(AgentConfig.get("plugin.file-io.enabled", "false"));
    }

    @Override
    public List<String> targetClassPrefixes() {
        return Arrays.asList(
            "java/io/FileInputStream",
            "java/io/FileOutputStream"
        );
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(
            new FileInputStreamTransformer(),
            new FileOutputStreamTransformer()
        );
    }

    // -----------------------------------------------------------------------
    // FileInputStream transformer
    // -----------------------------------------------------------------------

    static class FileInputStreamTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"java/io/FileInputStream".equals(className)) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        // Target: read(byte[], int, int) → int
                        if ("read".equals(name) && "([BII)I".equals(descriptor)) {
                            return new FileInputStreamAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class FileInputStreamAdvice extends BaseAdvice {
        private int pathId;

        protected FileInputStreamAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            captureStartTime();
            pathId = newLocal(Type.getType(String.class));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                "org/example/agent/plugin/fileio/FilePathExtractor", "extract",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, pathId);
        }

        @Override
        protected void onMethodExit(int opcode) {
            mv.visitVarInsn(ALOAD, pathId);
            // sizeBytes = (long) len param — index 3: (this, byte[], off, len)
            mv.visitVarInsn(ILOAD, 3);
            mv.visitInsn(I2L);
            calculateDurationAndPush();
            mv.visitInsn(opcode == ATHROW ? ICONST_0 : ICONST_1);
            mv.visitMethodInsn(INVOKESTATIC,
                "org/example/agent/core/TraceRuntime", "onFileRead",
                "(Ljava/lang/String;JJZ)V", false);
        }
    }

    // -----------------------------------------------------------------------
    // FileOutputStream transformer
    // -----------------------------------------------------------------------

    static class FileOutputStreamTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"java/io/FileOutputStream".equals(className)) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        // Target: write(byte[], int, int) → void
                        if ("write".equals(name) && "([BII)V".equals(descriptor)) {
                            return new FileOutputStreamAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class FileOutputStreamAdvice extends BaseAdvice {
        private int pathId;

        protected FileOutputStreamAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            captureStartTime();
            pathId = newLocal(Type.getType(String.class));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                "org/example/agent/plugin/fileio/FilePathExtractor", "extract",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, pathId);
        }

        @Override
        protected void onMethodExit(int opcode) {
            mv.visitVarInsn(ALOAD, pathId);
            // sizeBytes = (long) len param — index 3: (this, byte[], off, len)
            mv.visitVarInsn(ILOAD, 3);
            mv.visitInsn(I2L);
            calculateDurationAndPush();
            mv.visitInsn(opcode == ATHROW ? ICONST_0 : ICONST_1);
            mv.visitMethodInsn(INVOKESTATIC,
                "org/example/agent/core/TraceRuntime", "onFileWrite",
                "(Ljava/lang/String;JJZ)V", false);
        }
    }
}
