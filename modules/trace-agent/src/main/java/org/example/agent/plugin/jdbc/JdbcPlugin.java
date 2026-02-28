package org.example.agent.plugin.jdbc;

import org.example.agent.TracerPlugin;
import org.example.agent.plugin.BaseAdvice;
import org.example.agent.plugin.ReflectionUtils;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

public class JdbcPlugin implements TracerPlugin {

    @Override public String pluginId() { return "jdbc"; }

    @Override
    public List<String> targetClassPrefixes() {
        // Use jdbc sub-package prefixes, NOT root driver packages.
        // Broad prefixes like "org/h2/" would bypass BASE_IGNORE_PACKAGES (explicit-match rule)
        // and expose internal abstract classes (e.g. org/h2/value/Value) to the transformer
        // pipeline, causing a LinkageError (duplicate abstract class definition) on JDK 17+.
        // Narrowing to the jdbc sub-package keeps the ignore list effective for everything else.
        //   MySQL 5.x: com/mysql/jdbc/PreparedStatement
        //   MySQL 8.x: com/mysql/cj/jdbc/ClientPreparedStatement, ServerPreparedStatement
        //   PostgreSQL: org/postgresql/jdbc/PgPreparedStatement
        //   H2: org/h2/jdbc/JdbcPreparedStatement
        //   SQL Server: com/microsoft/sqlserver/jdbc/SQLServerPreparedStatement
        //   Oracle: oracle/jdbc/OraclePreparedStatement
        return Arrays.asList(
            "com/mysql/jdbc/",
            "com/mysql/cj/jdbc/",
            "org/postgresql/jdbc/",
            "org/h2/jdbc/",
            "com/microsoft/sqlserver/jdbc/",
            "oracle/jdbc/"
        );
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(new JdbcStatementTransformer());
    }

    static class JdbcStatementTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalizedName = className.replace('.', '/');
            if (!normalizedName.endsWith("PreparedStatement")) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (("execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name) || "executeLargeUpdate".equals(name)) && descriptor.startsWith("()")) {
                            return new JdbcStatementAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class JdbcStatementAdvice extends BaseAdvice {
        private boolean ignored = false;

        protected JdbcStatementAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.contains("TraceIgnore")) {
                ignored = true;
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        protected void onMethodEnter() {
            if (ignored) return;
            captureStartTime();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/jdbc/JdbcPlugin", "extractSql", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onDbQueryStart", "(Ljava/lang/String;)V", false);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (ignored) return;
            if (opcode == ATHROW) {
                // Stack: [..., throwable] — DUP so the original throwable survives for re-throw
                mv.visitInsn(DUP);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/jdbc/JdbcPlugin", "extractSql", "(Ljava/lang/Object;)Ljava/lang/String;", false);
                calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onDbQueryError",
                    "(Ljava/lang/Throwable;Ljava/lang/String;J)V", false);
                return;
            }
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/jdbc/JdbcPlugin", "extractSql", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            calculateDurationAndPush();
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onDbQueryEnd",
                "(Ljava/lang/String;J)V", false);
        }
    }

    public static String extractSql(Object statement) {
        if (statement == null) return "unknown-sql";
        String className = statement.getClass().getName();
        if (className.contains("mysql")) {
            return ReflectionUtils.getFieldValue(statement, "query")
                .map(Object::toString)
                .orElseGet(statement::toString);
        }
        return statement.toString();
    }
}
