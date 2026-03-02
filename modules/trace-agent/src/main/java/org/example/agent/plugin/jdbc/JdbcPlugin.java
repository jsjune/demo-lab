package org.example.agent.plugin.jdbc;

import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
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
        return AgentConfig.getPluginTargetPrefixes(pluginId(), Arrays.asList(
            "com/mysql/jdbc/",
            "com/mysql/cj/jdbc/",
            "org/mariadb/jdbc/",
            "org/postgresql/jdbc/",
            "org/h2/jdbc/",
            "com/microsoft/sqlserver/jdbc/",
            "oracle/jdbc/"
        ));
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(new JdbcStatementTransformer());
    }

    static class JdbcStatementTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalizedName = className == null ? "" : className.replace('.', '/');
            if (!normalizedName.contains("PreparedStatement") && !normalizedName.contains("CallableStatement")) return null;
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
        private int sqlId;
        private int dbHostId;

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

            // sql 로컬 변수 할당 및 저장 — onMethodExit에서 ALOAD로 재사용
            sqlId = newLocal(Type.getType(String.class));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/jdbc/JdbcPlugin", "extractSql", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, sqlId);

            // dbHost 로컬 변수 할당 및 저장 — reflection 3단 체인 비용을 onMethodEnter에서 1회만 지불
            dbHostId = newLocal(Type.getType(String.class));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/jdbc/JdbcPlugin", "extractDbHost", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, dbHostId);

            mv.visitVarInsn(ALOAD, sqlId);
            mv.visitVarInsn(ALOAD, dbHostId);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onDbQueryStart", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (ignored) return;
            if (opcode == ATHROW) {
                // Stack: [..., throwable] — DUP so the original throwable survives for re-throw
                mv.visitInsn(DUP);
                mv.visitVarInsn(ALOAD, sqlId);      // 저장된 sql 재사용
                calculateDurationAndPush();
                mv.visitVarInsn(ALOAD, dbHostId);   // 저장된 dbHost 재사용
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onDbQueryError",
                    "(Ljava/lang/Throwable;Ljava/lang/String;JLjava/lang/String;)V", false);
                return;
            }
            mv.visitVarInsn(ALOAD, sqlId);          // 저장된 sql 재사용
            calculateDurationAndPush();
            mv.visitVarInsn(ALOAD, dbHostId);       // 저장된 dbHost 재사용
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onDbQueryEnd",
                "(Ljava/lang/String;JLjava/lang/String;)V", false);
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

    public static String extractDbHost(Object statement) {
        if (statement == null) return "unknown-db";
        try {
            Object connection = ReflectionUtils.invokeMethod(statement, "getConnection").orElse(null);
            if (connection == null) return "unknown-db";
            Object metaData = ReflectionUtils.invokeMethod(connection, "getMetaData").orElse(null);
            if (metaData == null) return "unknown-db";
            Object url = ReflectionUtils.invokeMethod(metaData, "getURL").orElse(null);
            if (url == null) return "unknown-db";
            return parseDbHost(url.toString());
        } catch (Throwable e) {
            return "unknown-db";
        }
    }

    public static String parseDbHost(String jdbcUrl) {
        if (jdbcUrl == null) return "unknown-db";
        try {
            // Remove "jdbc:" prefix → e.g. "mysql://host:3306/db" or "h2:mem:testdb"
            String url = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
            int schemeEnd = url.indexOf(':');
            if (schemeEnd < 0) return url;
            String scheme = url.substring(0, schemeEnd);
            String rest = url.substring(schemeEnd + 1);
            if (rest.startsWith("//")) {
                // Network address: mysql://host:3306/db, postgresql://host:5432/db
                String hostPart = rest.substring(2);
                // Strip credentials before host: jdbc:mysql://user:pass@host:3306/db → host:3306
                int atIdx = hostPart.indexOf('@');
                if (atIdx >= 0) hostPart = hostPart.substring(atIdx + 1);
                int slashIdx = hostPart.indexOf('/');
                if (slashIdx > 0) hostPart = hostPart.substring(0, slashIdx);
                int semiIdx = hostPart.indexOf(';');   // SQL Server: host:port;key=val
                if (semiIdx > 0) hostPart = hostPart.substring(0, semiIdx);
                return scheme + "://" + hostPart;
            }
            // Embedded / file DB: h2:mem:testdb, h2:file:/path/to/db
            return scheme + ":" + (rest.length() > 30 ? rest.substring(0, 30) + "..." : rest);
        } catch (Throwable e) {
            return jdbcUrl.length() > 50 ? jdbcUrl.substring(0, 50) : jdbcUrl;
        }
    }
}
