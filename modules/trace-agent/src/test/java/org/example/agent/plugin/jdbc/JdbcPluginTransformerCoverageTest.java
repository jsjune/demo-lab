package org.example.agent.plugin.jdbc;

import org.example.agent.testutil.AsmTestUtils;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcPluginTransformerCoverageTest {

    @Test
    void jdbcStatementTransformer_transformsPreparedStatementExecute() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "org/postgresql/jdbc/PgPreparedStatement",
            AsmTestUtils.MethodSpec.of("execute", "()Z"),
            AsmTestUtils.MethodSpec.of("executeQuery", "()Ljava/sql/ResultSet;"));

        JdbcPlugin.JdbcStatementTransformer t = new JdbcPlugin.JdbcStatementTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "org/postgresql/jdbc/PgPreparedStatement",
            null, null, original);

        assertNotNull(out);
    }

    @Test
    void jdbcStatementTransformer_nonStatement_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods("com/example/NotStatement");
        JdbcPlugin.JdbcStatementTransformer t = new JdbcPlugin.JdbcStatementTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "com/example/NotStatement", null, null, original));
    }

    @Test
    void parseDbHost_handlesNetworkAndEmbeddedUrls() {
        assertEquals("mysql://localhost:3306", JdbcPlugin.parseDbHost("jdbc:mysql://localhost:3306/test"));
        assertEquals("postgresql://db.example.com:5432",
            JdbcPlugin.parseDbHost("jdbc:postgresql://db.example.com:5432/app"));
        assertTrue(JdbcPlugin.parseDbHost("jdbc:h2:mem:testdb").startsWith("h2:mem:testdb"));
    }

    @Test
    void parseDbHost_null_returnsUnknown() {
        assertEquals("unknown-db", JdbcPlugin.parseDbHost(null));
    }

    @Test
    void extractDbHost_fallbackAndSuccessPaths() {
        assertEquals("unknown-db", JdbcPlugin.extractDbHost(null));
        assertEquals("unknown-db", JdbcPlugin.extractDbHost(new NoConnectionStatement()));

        String host = JdbcPlugin.extractDbHost(new FullChainStatement("jdbc:mysql://127.0.0.1:3306/mydb"));
        assertEquals("mysql://127.0.0.1:3306", host);
    }

    @Test
    void extractSql_nonMysql_usesToString() {
        assertEquals("SQL:select 1", JdbcPlugin.extractSql(new SimpleStatement("SQL:select 1")));
    }

    @Test
    void pluginMetadata_andTargetPrefixes() {
        JdbcPlugin p = new JdbcPlugin();
        assertEquals("jdbc", p.pluginId());
        assertEquals(1, p.transformers().size());
        List<String> prefixes = p.targetClassPrefixes();
        assertTrue(prefixes.stream().anyMatch(s -> s.contains("jdbc")));
    }

    @Test
    void parseDbHost_extraBranches() {
        assertEquals("not-a-url", JdbcPlugin.parseDbHost("not-a-url"));
        assertEquals("mysql://host:3306", JdbcPlugin.parseDbHost("jdbc:mysql://u:p@host:3306/db"));
        assertEquals("sqlserver://host:1433", JdbcPlugin.parseDbHost("jdbc:sqlserver://host:1433;databaseName=x"));
    }

    @Test
    void extractSql_mysqlBranch_readsQueryField() throws Exception {
        byte[] bytes = mysqlLikePreparedStatementBytes();
        Class<?> clazz = new DefineClassLoader().define("com.mysql.jdbc.FakePreparedStatement", bytes);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Field query = clazz.getDeclaredField("query");
        query.setAccessible(true);
        query.set(instance, "SELECT * FROM user");

        assertEquals("SELECT * FROM user", JdbcPlugin.extractSql(instance));
    }

    static class NoConnectionStatement {
        @SuppressWarnings("unused")
        public Object getConnection() {
            return null;
        }
    }

    static class FullChainStatement {
        private final String url;

        FullChainStatement(String url) {
            this.url = url;
        }

        @SuppressWarnings("unused")
        public Object getConnection() {
            return new Object() {
                @SuppressWarnings("unused")
                public Object getMetaData() {
                    return new Object() {
                        @SuppressWarnings("unused")
                        public String getURL() {
                            return url;
                        }
                    };
                }
            };
        }
    }

    static class SimpleStatement {
        private final String text;

        SimpleStatement(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    static class DefineClassLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static byte[] mysqlLikePreparedStatementBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String n = "com/mysql/jdbc/FakePreparedStatement";
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, n, null, "java/lang/Object", null);
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "query", "Ljava/lang/Object;", null, null);
        fv.visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor toString = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        toString.visitCode();
        toString.visitLdcInsn("fallback-toString");
        toString.visitInsn(Opcodes.ARETURN);
        toString.visitMaxs(0, 0);
        toString.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
