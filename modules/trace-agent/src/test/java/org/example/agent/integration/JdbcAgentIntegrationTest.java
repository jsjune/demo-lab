package org.example.agent.integration;

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
     */
    public static class FakePreparedStatement {
        private final String sql;

        public FakePreparedStatement(String sql) {
            this.sql = sql;
        }

        /** JdbcStatementAdvice 주입 대상: descriptor "()Z" → startsWith("()") 통과 */
        public boolean execute() {
            return true;
        }

        /** JdbcStatementAdvice 주입 대상: descriptor "()Ljava/sql/ResultSet;" → startsWith("()") 통과 */
        public java.sql.ResultSet executeQuery() {
            return null;
        }

        @Override
        public String toString() {
            return sql;
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
                () -> TraceRuntime.onDbQueryStart(eq("SELECT 1")),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(eq("SELECT 1"), anyLong()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryError(any(), anyString(), anyLong()),
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
                () -> TraceRuntime.onDbQueryStart(anyString()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryError(
                    any(RuntimeException.class), anyString(), anyLong()),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(anyString(), anyLong()),
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
                () -> TraceRuntime.onDbQueryStart(eq("SELECT name FROM users")),
                times(1)
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(eq("SELECT name FROM users"), anyLong()),
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
                () -> TraceRuntime.onDbQueryStart(anyString()),
                never()
            );
            mock.verify(
                () -> TraceRuntime.onDbQueryEnd(anyString(), anyLong()),
                never()
            );
        }
    }
}
