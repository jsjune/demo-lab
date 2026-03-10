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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("통합: JDBC PreparedStatement 바이트코드 변환 검증")
class JdbcAgentIntegrationTest extends ByteBuddyIntegrationTest {

    @BeforeEach
    void setUp() { TxIdHolder.clear(); }

    @AfterEach
    void tearDown() { TxIdHolder.clear(); }

    // -----------------------------------------------------------------------
    // Fake target classes
    // -----------------------------------------------------------------------

    public static class FakePreparedStatement {
        private final String sql;
        private final String jdbcUrl;

        public FakePreparedStatement(String sql) { this(sql, null); }
        public FakePreparedStatement(String sql, String jdbcUrl) {
            this.sql = sql;
            this.jdbcUrl = jdbcUrl;
        }

        public boolean execute() { return true; }
        public java.sql.ResultSet executeQuery() { return null; }
        public Object getConnection() {
            return jdbcUrl == null ? null : new JdbcChainHelper.FakeConnection(jdbcUrl);
        }
        @Override public String toString() { return sql; }
    }

    public static class ThrowingPreparedStatement {
        public boolean execute() { throw new RuntimeException("simulated query failure"); }
        @Override public String toString() { return "SELECT boom"; }
    }

    public static class FakeIgnoredPreparedStatement {
        @TraceIgnore public boolean execute() { return false; }
        @Override public String toString() { return "SELECT ignored"; }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("execute 정상 종료 시 onDbQueryStart와 onDbQueryEnd가 호출되어야 한다")
    void execute_정상종료_onDbQueryStart와onDbQueryEnd가호출되어야한다() throws Exception {
        Class<?> cls = instrument(FakePreparedStatement.class, JdbcPlugin.JdbcStatementAdvice.class,
            namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate").and(takesNoArguments()));

        Object instance = cls.getDeclaredConstructor(String.class).newInstance("SELECT 1");
        Method method = cls.getDeclaredMethod("execute");

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(() -> TraceRuntime.onDbQueryStart(eq("SELECT 1"), anyString()), times(1));
            mock.verify(() -> TraceRuntime.onDbQueryEnd(eq("SELECT 1"), anyLong(), anyString()), times(1));
            mock.verify(() -> TraceRuntime.onDbQueryError(any(Throwable.class), anyString(), anyLong(), anyString()), never());
        }
    }

    @Test
    @DisplayName("execute 예외 발생 시 onDbQueryError가 호출되고 onDbQueryEnd는 호출되지 않아야 한다")
    void execute_예외발생_onDbQueryError가호출되어야한다() throws Exception {
        Class<?> cls = instrument(ThrowingPreparedStatement.class, JdbcPlugin.JdbcStatementAdvice.class,
            namedOneOf("execute", "executeQuery").and(takesNoArguments()));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method method = cls.getDeclaredMethod("execute");

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            assertThrows(InvocationTargetException.class, () -> method.invoke(instance));

            mock.verify(() -> TraceRuntime.onDbQueryStart(anyString(), anyString()), times(1));
            mock.verify(() -> TraceRuntime.onDbQueryError(any(RuntimeException.class), anyString(), anyLong(), anyString()), times(1));
            mock.verify(() -> TraceRuntime.onDbQueryEnd(anyString(), anyLong(), anyString()), never());
        }
    }

    @Test
    @DisplayName("executeQuery 정상 종료 시 추적되어야 한다")
    void executeQuery_정상종료_추적되어야한다() throws Exception {
        Class<?> cls = instrument(FakePreparedStatement.class, JdbcPlugin.JdbcStatementAdvice.class,
            namedOneOf("execute", "executeQuery").and(takesNoArguments()));

        Object instance = cls.getDeclaredConstructor(String.class).newInstance("SELECT name FROM users");
        Method method = cls.getDeclaredMethod("executeQuery");

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(() -> TraceRuntime.onDbQueryStart(eq("SELECT name FROM users"), anyString()), times(1));
            mock.verify(() -> TraceRuntime.onDbQueryEnd(eq("SELECT name FROM users"), anyLong(), anyString()), times(1));
        }
    }

    @Test
    @DisplayName("@TraceIgnore 어노테이션이 있으면 추적을 건너뛰어야 한다")
    void traceIgnore_어노테이션이있으면추적을건너뛰어야한다() throws Exception {
        Class<?> cls = instrument(FakeIgnoredPreparedStatement.class, JdbcPlugin.JdbcStatementAdvice.class,
            namedOneOf("execute", "executeQuery").and(takesNoArguments())
                .and(not(isAnnotatedWith(TraceIgnore.class))));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method method = cls.getDeclaredMethod("execute");

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(() -> TraceRuntime.onDbQueryStart(anyString(), anyString()), never());
            mock.verify(() -> TraceRuntime.onDbQueryEnd(anyString(), anyLong(), anyString()), never());
        }
    }

    // -----------------------------------------------------------------------
    // parseDbHost unit tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("parseDbHost: h2 embedded URL")
    void parseDbHost_h2임베디드() {
        assertEquals("h2:mem:testdb", JdbcPlugin.parseDbHost("jdbc:h2:mem:testdb"));
    }

    @Test
    @DisplayName("parseDbHost: mysql network URL")
    void parseDbHost_mysql() {
        assertEquals("mysql://localhost:3306", JdbcPlugin.parseDbHost("jdbc:mysql://localhost:3306/mydb"));
    }

    @Test
    @DisplayName("parseDbHost: credential 포함 mysql URL")
    void parseDbHost_mysql_credential() {
        assertEquals("mysql://prod-db:3306", JdbcPlugin.parseDbHost("jdbc:mysql://user:pass@prod-db:3306/orders"));
    }

    @Test
    @DisplayName("parseDbHost: postgresql URL")
    void parseDbHost_postgresql() {
        assertEquals("postgresql://db.example.com", JdbcPlugin.parseDbHost("jdbc:postgresql://db.example.com/myapp"));
    }

    @Test
    @DisplayName("execute: jdbcUrl이 있으면 실제 dbHost로 onDbQueryEnd 호출")
    void execute_jdbcUrl있음_실제dbHost검증() throws Exception {
        Class<?> cls = instrument(FakePreparedStatement.class, JdbcPlugin.JdbcStatementAdvice.class,
            namedOneOf("execute", "executeQuery").and(takesNoArguments()));

        Object instance = cls.getDeclaredConstructor(String.class, String.class)
            .newInstance("SELECT 1", "jdbc:mysql://localhost:3306/testdb");
        Method method = cls.getDeclaredMethod("execute");

        try (MockedStatic<TraceRuntime> mock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance);

            mock.verify(() -> TraceRuntime.onDbQueryStart(eq("SELECT 1"), eq("mysql://localhost:3306")), times(1));
            mock.verify(() -> TraceRuntime.onDbQueryEnd(eq("SELECT 1"), anyLong(), eq("mysql://localhost:3306")), times(1));
        }
    }
}
