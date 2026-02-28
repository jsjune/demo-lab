package org.example.agent.plugin.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
}
