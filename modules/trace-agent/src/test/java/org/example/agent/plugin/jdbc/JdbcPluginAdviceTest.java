package org.example.agent.plugin.jdbc;

import org.example.agent.core.TraceRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("플러그인: JDBC (@Advice 메서드 검증)")
class JdbcPluginAdviceTest {

    // -----------------------------------------------------------------------
    // JdbcStatementAdvice
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("enter: onDbQueryStart가 호출되어야 한다")
    void enter_callsOnDbQueryStart() {
        try (MockedStatic<JdbcPlugin> jp = mockStatic(JdbcPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
             MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {

            Object fakeStmt = new Object();
            jp.when(() -> JdbcPlugin.extractSql(any())).thenReturn("SELECT 1");
            jp.when(() -> JdbcPlugin.extractDbHost(any())).thenReturn("mysql://localhost:3306");

            JdbcPlugin.JdbcStatementAdvice.enter(fakeStmt, null, null, 0L);

            rt.verify(() -> TraceRuntime.onDbQueryStart(eq("SELECT 1"), eq("mysql://localhost:3306")), times(1));
        }
    }

    @Test
    @DisplayName("exit: 정상 종료 시 onDbQueryEnd가 호출되어야 한다")
    void exit_normal_callsOnDbQueryEnd() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            long startTime = System.currentTimeMillis() - 100;
            JdbcPlugin.JdbcStatementAdvice.exit(null, "SELECT 1", "mysql://localhost", startTime);
            rt.verify(() -> TraceRuntime.onDbQueryEnd(eq("SELECT 1"), anyLong(), eq("mysql://localhost")), times(1));
            rt.verify(() -> TraceRuntime.onDbQueryError(any(), anyString(), anyLong(), anyString()), never());
        }
    }

    @Test
    @DisplayName("exit: 예외 발생 시 onDbQueryError가 호출되어야 한다")
    void exit_thrown_callsOnDbQueryError() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            Throwable err = new RuntimeException("db fail");
            long startTime = System.currentTimeMillis() - 50;
            JdbcPlugin.JdbcStatementAdvice.exit(err, "SELECT boom", "mysql://host", startTime);
            rt.verify(() -> TraceRuntime.onDbQueryError(eq(err), eq("SELECT boom"), anyLong(), eq("mysql://host")), times(1));
            rt.verify(() -> TraceRuntime.onDbQueryEnd(anyString(), anyLong(), anyString()), never());
        }
    }
}
