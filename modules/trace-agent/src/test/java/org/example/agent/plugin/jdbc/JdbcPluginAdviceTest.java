package org.example.agent.plugin.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("플러그인: JDBC (Advice 바이트코드 검증)")
class JdbcPluginAdviceTest {

    @Test
    @DisplayName("PreparedStatement.execute 진입 시 onDbQueryStart가 호출되어야 한다")
    void testJdbcStatementAdviceOnMethodEnter() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        JdbcPlugin.JdbcStatementAdvice advice = new JdbcPlugin.JdbcStatementAdvice(mv, Opcodes.ACC_PUBLIC, "execute", "()Z");

        advice.onMethodEnter();

        // Verify that it calls TraceRuntime.onDbQueryStart
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onDbQueryStart"),
            anyString(),
            eq(false)
        );
    }

    @Test
    @DisplayName("PreparedStatement.execute 종료 시 onDbQueryEnd가 호출되어야 한다")
    void testJdbcStatementAdviceOnMethodExit() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        JdbcPlugin.JdbcStatementAdvice advice = new JdbcPlugin.JdbcStatementAdvice(mv, Opcodes.ACC_PUBLIC, "execute", "()Z");

        advice.onMethodEnter();
        // Normal exit (opcode 0 doesn't matter much for our check but usually IRETURN etc)
        advice.onMethodExit(Opcodes.IRETURN);

        // Verify that it calls TraceRuntime.onDbQueryEnd
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onDbQueryEnd"),
            anyString(),
            eq(false)
        );
    }

    @Test
    @DisplayName("PreparedStatement.execute 예외 종료 시 onDbQueryError가 호출되어야 한다")
    void testJdbcStatementAdviceOnMethodExit_throw_callsError() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        JdbcPlugin.JdbcStatementAdvice advice = new JdbcPlugin.JdbcStatementAdvice(mv, Opcodes.ACC_PUBLIC, "execute", "()Z");

        advice.onMethodEnter();
        advice.onMethodExit(Opcodes.ATHROW);

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onDbQueryError"),
            eq("(Ljava/lang/Throwable;Ljava/lang/String;JLjava/lang/String;)V"),
            eq(false)
        );
    }

    @Test
    @DisplayName("@TraceIgnore 애노테이션이면 DB 시작/종료 호출을 삽입하지 않아야 한다")
    void testJdbcStatementAdvice_traceIgnore_skips() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        JdbcPlugin.JdbcStatementAdvice advice = new JdbcPlugin.JdbcStatementAdvice(mv, Opcodes.ACC_PUBLIC, "execute", "()Z");

        advice.visitAnnotation("Lorg/example/TraceIgnore;", true);
        advice.onMethodEnter();
        advice.onMethodExit(Opcodes.IRETURN);

        verify(mv, never()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onDbQueryStart"),
            anyString(),
            eq(false)
        );
    }
}
