package org.example.agent.plugin.jdbc;

import org.example.agent.testutil.AsmTestUtils;
import org.junit.jupiter.api.Test;

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
}

