package org.example.agent.instrumentation;

import org.example.agent.core.TraceIgnore;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.agent.plugin.jdbc.JdbcPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 통합 테스트: JdbcStatementTransformer 바이트코드 변환 후 실제 실행으로
 * TraceRuntime 호출 여부를 검증한다.
 *
 * <p>Inner class명이 PreparedStatement로 끝나야 JdbcStatementTransformer 필터를 통과한다.
 */
@DisplayName("통합: JDBC PreparedStatement 바이트코드 변환 검증")
class JdbcAgentIntegrationTest extends ByteBuddyIntegrationTest {

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TxIdHolder.clear();
    }

    // -----------------------------------------------------------------------
    // Inner Classes — 클래스명이 PreparedStatement로 끝나야 변환기 필터 통과
    // -----------------------------------------------------------------------

    /**
     * 정상 종료 케이스용.
     * toString()이 sql을 반환하므로 JdbcPlugin.extractSql()에서 SQL 문자열 획득 가능.
     * jdbcUrl을 지정하면 getConnection() 체인을 통해 extractDbHost()가 실제 host를 반환한다.
     */
    public static class FakePreparedStatement {
        private final String sql;
        private final String jdbcUrl;

        public FakePreparedStatement(String sql) {
            this(sql, null);
        }

        public FakePreparedStatement(String sql, String jdbcUrl) {
            this.sql = sql;
            this.jdbcUrl = jdbcUrl;
        }

        /** JdbcStatementAdvice 주입 대상: descriptor "()Z" → startsWith("()") 통과 */
        public boolean execute() {
            return true;
        }

        /** JdbcStatementAdvice 주입 대상: descriptor "()Ljava/sql/ResultSet;" → startsWith("()") 통과 */
        public java.sql.ResultSet executeQuery() {
            return null;
        }

        /** jdbcUrl null이면 null 반환 → extractDbHost "unknown-db" 경로 */
        public FakeDbConnection getConnection() {
            if (jdbcUrl == null) return null;
            return new FakeDbConnection(jdbcUrl);
        }

        @Override
        public String toString() {
            return sql;
        }
    }

    public static class FakeDbConnection {
        private final String url;

        public FakeDbConnection(String url) {
            this.url = url;
        }

        public FakeDbMeta getMetaData() {
            return new FakeDbMeta(url);
        }
    }

    public static class FakeDbMeta {
        private final String url;

        public FakeDbMeta(String url) {
            this.url = url;
        }

        public String getURL() {
            return url;
        }
    }

    /**
     * ATHROW 경로 케이스용.
     * execute() 호출 시 RuntimeException을 던진다.
     */
    public static class ThrowingPreparedStatement {

        public boolean execute() {
            throw new RuntimeException("simulated query failure");
        }

        @Override
        public String toString() {
            return "SELECT boom";
        }
    }

    /**
     * @TraceIgnore 케이스용.
     * execute()에 @TraceIgnore 어노테이션이 붙어 있어 추적을 건너뛰어야 한다.
     */
    public static class FakeIgnoredPreparedStatement {

        @TraceIgnore
        public boolean execute() {
            return false;
        }

        @Override
        public String toString() {
            return "SELECT ignored";
        }
    }

    /**
     * Statement(sql) 경로 케이스용.
     */
    public static class FakeStatement {
        private final String jdbcUrl;

        public FakeStatement(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public boolean execute(String sql) {
            return true;
        }

        public FakeDbConnection getConnection() {
            return new FakeDbConnection(jdbcUrl);
        }
    }

    /**
     * prepareStatement(sql) 예외 경로 케이스용.
     */
    public static class ThrowingConnection {
        private final String jdbcUrl;

        public ThrowingConnection(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public Object prepareStatement(String sql) {
            throw new RuntimeException("prepare failed");
        }

        public FakeDbMeta getMetaData() {
            return new FakeDbMeta(jdbcUrl);
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("execute 정상 종료 시 onDbQueryStart와 onDbQueryEnd가 호출되어야 한다")
    void execute_정상종료_onDbQueryStart와onDbQueryEnd가호출되어야한다() throws Exception {
        JdbcPlugin plugin = new JdbcPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> cls = transformAndLoad(FakePreparedStatement.class, transformer);

        Object instance = cls.getDeclaredConstructor(String.class).newInstance("SELECT 1");
        Method method = cls.getDeclaredMethod("execute");
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(
                () -> TraceRuntime.onDbQueryStart(eq("SELECT 1"), anyString()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(eq("SELECT 1"), anyLong(), anyString()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryError(any(), anyString(), anyLong(), anyString()),
                never()
            );
        }
    }

    @Test
    @DisplayName("execute 예외 발생 시 onDbQueryError가 호출되고 onDbQueryEnd는 호출되지 않아야 한다")
    void execute_예외발생_onDbQueryError가호출되어야한다() throws Exception {
        JdbcPlugin plugin = new JdbcPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> cls = transformAndLoad(ThrowingPreparedStatement.class, transformer);

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method method = cls.getDeclaredMethod("execute");
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            // method.invoke()는 내부 예외를 InvocationTargetException으로 감싼다
            assertThrows(InvocationTargetException.class, () -> method.invoke(instance));

            mock.verify(
                () -> TraceRuntime.onDbQueryStart(anyString(), anyString()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryError(
                    any(RuntimeException.class), anyString(), anyLong(), anyString()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(anyString(), anyLong(), anyString()),
                never()
            );
        }
    }

    @Test
    @DisplayName("executeQuery 정상 종료 시 onDbQueryStart와 onDbQueryEnd가 호출되어야 한다")
    void executeQuery_정상종료_추적되어야한다() throws Exception {
        JdbcPlugin plugin = new JdbcPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> cls = transformAndLoad(FakePreparedStatement.class, transformer);

        Object instance = cls.getDeclaredConstructor(String.class).newInstance("SELECT name FROM users");
        Method method = cls.getDeclaredMethod("executeQuery");
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(
                () -> TraceRuntime.onDbQueryStart(eq("SELECT name FROM users"), anyString()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(eq("SELECT name FROM users"), anyLong(), anyString()),
                times(1)
            );
        }
    }

    @Test
    @DisplayName("@TraceIgnore 어노테이션이 있으면 추적을 건너뛰어야 한다")
    void traceIgnore_어노테이션이있으면추적을건너뛰어야한다() throws Exception {
        JdbcPlugin plugin = new JdbcPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> cls = transformAndLoad(FakeIgnoredPreparedStatement.class, transformer);

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method method = cls.getDeclaredMethod("execute");
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(
                () -> TraceRuntime.onDbQueryStart(anyString(), anyString()),
                never()
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(anyString(), anyLong(), anyString()),
                never()
            );
        }
    }

    // -----------------------------------------------------------------------
    // parseDbHost 직접 단위 테스트
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("parseDbHost: h2 embedded URL은 scheme:rest 형식을 반환해야 한다")
    void parseDbHost_h2임베디드_schemeAndRest() {
        assertEquals("h2:mem:testdb", JdbcPlugin.parseDbHost("jdbc:h2:mem:testdb"));
    }

    @Test
    @DisplayName("parseDbHost: mysql 네트워크 URL은 scheme://host:port를 반환해야 한다")
    void parseDbHost_mysql네트워크_schemeHostPort() {
        assertEquals("mysql://localhost:3306", JdbcPlugin.parseDbHost("jdbc:mysql://localhost:3306/mydb"));
    }

    @Test
    @DisplayName("parseDbHost: credential 포함 mysql URL에서 user:pass@를 제거해야 한다")
    void parseDbHost_mysql크리덴셜포함_크리덴셜제거() {
        assertEquals("mysql://prod-db:3306", JdbcPlugin.parseDbHost("jdbc:mysql://user:pass@prod-db:3306/orders"));
    }

    @Test
    @DisplayName("parseDbHost: postgresql URL은 scheme://host를 반환해야 한다")
    void parseDbHost_postgresql_schemeAndHost() {
        assertEquals("postgresql://db.example.com", JdbcPlugin.parseDbHost("jdbc:postgresql://db.example.com/myapp"));
    }

    // -----------------------------------------------------------------------
    // extractDbHost reflection 체인 검증 — FakePreparedStatement(sql, jdbcUrl)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("execute 정상 종료 시 jdbcUrl이 있으면 실제 dbHost 값으로 onDbQueryEnd가 호출되어야 한다")
    void execute_jdbcUrl있음_실제dbHost검증() throws Exception {
        JdbcPlugin plugin = new JdbcPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> cls = transformAndLoad(FakePreparedStatement.class, transformer);

        Object instance = cls.getDeclaredConstructor(String.class, String.class)
            .newInstance("SELECT 1", "jdbc:mysql://localhost:3306/testdb");
        Method method = cls.getDeclaredMethod("execute");
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(
                () -> TraceRuntime.onDbQueryStart(eq("SELECT 1"), eq("mysql://localhost:3306")),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(eq("SELECT 1"), anyLong(), eq("mysql://localhost:3306")),
                times(1)
            );
        }
    }

    @Test
    @DisplayName("Statement.execute(sql) 정상 종료 시 sql 인자로 onDbQueryStart/onDbQueryEnd가 호출되어야 한다")
    void statementExecute_sql인자_정상종료_추적되어야한다() throws Exception {
        JdbcPlugin plugin = new JdbcPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> cls = transformAndLoad(FakeStatement.class, transformer);

        Object instance = cls.getDeclaredConstructor(String.class)
            .newInstance("jdbc:mysql://localhost:3306/testdb");
        Method method = cls.getDeclaredMethod("execute", String.class);
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance, "SELECT 42");

            mock.verify(
                () -> TraceRuntime.onDbQueryStart(eq("SELECT 42"), eq("mysql://localhost:3306")),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(eq("SELECT 42"), anyLong(), eq("mysql://localhost:3306")),
                times(1)
            );
        }
    }

    @Test
    @DisplayName("prepareStatement(sql) 예외 발생 시 onDbQueryStart/onDbQueryError가 호출되어야 한다")
    void prepareStatement_예외발생_onDbQueryError가호출되어야한다() throws Exception {
        JdbcPlugin plugin = new JdbcPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(1);
        Class<?> cls = transformAndLoad(ThrowingConnection.class, transformer);

        Object instance = cls.getDeclaredConstructor(String.class)
            .newInstance("jdbc:mysql://localhost:3306/testdb");
        Method method = cls.getDeclaredMethod("prepareStatement", String.class);
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            assertThrows(InvocationTargetException.class, () -> method.invoke(instance, "SELECT bad"));

            mock.verify(
                () -> TraceRuntime.onDbQueryStart(eq("SELECT bad"), eq("mysql://localhost:3306")),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryError(
                    any(RuntimeException.class), eq("SELECT bad"), anyLong(), eq("mysql://localhost:3306")),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(anyString(), anyLong(), anyString()),
                never()
            );
        }
    }
}
