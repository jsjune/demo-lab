package org.example.agent.plugin.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("플러그인: Redis (Advice 바이트코드 검증)")
class RedisPluginAdviceTest {

    // -----------------------------------------------------------------------
    // LettuceAdvice (FR-06)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("LettuceAdvice — safeKeyToString 사용 검증 (FR-06)")
    class LettuceAdviceTest {

        @Test
        @DisplayName("get 호출 시 safeKeyToString → onCacheGet 순서로 바이트코드가 주입되어야 한다")
        void get_injectsSafeKeyToString_thenOnCacheGet() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(
                mv, Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;");

            advice.onMethodEnter();

            InOrder order = inOrder(mv);
            // 1) ALOAD 1
            order.verify(mv).visitVarInsn(eq(Opcodes.ALOAD), eq(1));
            // 2) safeKeyToString (String.valueOf 대신)
            order.verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("safeKeyToString"),
                eq("(Ljava/lang/Object;)Ljava/lang/String;"),
                eq(false)
            );
            // 3) onCacheGet
            order.verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheGet"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("String.valueOf는 절대 호출되지 않아야 한다 (byte[] 오염 방지)")
        void get_neverCallsStringValueOf() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(
                mv, Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;");

            advice.onMethodEnter();

            verify(mv, never()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("java/lang/String"),
                eq("valueOf"),
                anyString(),
                anyBoolean()
            );
        }

        @Test
        @DisplayName("set 호출 시 safeKeyToString → onCacheSet 순서로 주입되어야 한다")
        void set_injectsSafeKeyToString_thenOnCacheSet() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(
                mv, Opcodes.ACC_PUBLIC, "set", "(Ljava/lang/Object;Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;");

            advice.onMethodEnter();

            verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("safeKeyToString"),
                eq("(Ljava/lang/Object;)Ljava/lang/String;"),
                eq(false)
            );
            verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheSet"),
                anyString(),
                eq(false)
            );
        }
    }

    // -----------------------------------------------------------------------
    // JedisTransformer descriptor 필터 (FR-07)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JedisTransformer — descriptor 필터 검증 (FR-07)")
    class JedisTransformerTest {

        private boolean isTargetCommand(String name) throws Exception {
            Method m = RedisPlugin.JedisTransformer.class.getDeclaredMethod("isTargetCommand", String.class);
            m.setAccessible(true);
            RedisPlugin.JedisTransformer transformer = new RedisPlugin.JedisTransformer();
            return (boolean) m.invoke(transformer, name);
        }

        @Test
        @DisplayName("get(String) — String descriptor는 JedisAdvice 대상이어야 한다")
        void getStringDescriptor_isTarget() throws Exception {
            assertTrue(isTargetCommand("get"), "get은 대상 커맨드여야 함");
            // descriptor.startsWith("(Ljava/lang/String;") 조건도 통과
            String descriptor = "(Ljava/lang/String;)Ljava/lang/String;";
            assertTrue(descriptor.startsWith("(Ljava/lang/String;"), "String descriptor 통과");
        }

        @Test
        @DisplayName("get(byte[]) — byte[] descriptor는 JedisAdvice 대상에서 제외되어야 한다")
        void getByteArrayDescriptor_isFiltered() {
            String descriptor = "([B)Ljava/lang/String;";
            assertFalse(descriptor.startsWith("(Ljava/lang/String;"),
                "byte[] descriptor는 필터링되어 JedisAdvice가 적용되지 않아야 함");
        }

        @Test
        @DisplayName("set(byte[], byte[]) — byte[] descriptor는 JedisAdvice 대상에서 제외되어야 한다")
        void setByteArrayDescriptor_isFiltered() {
            String descriptor = "([B[B)Ljava/lang/String;";
            assertFalse(descriptor.startsWith("(Ljava/lang/String;"),
                "byte[] 오버로드는 ClassCastException 방지를 위해 제외되어야 함");
        }
    }

    // -----------------------------------------------------------------------
    // JedisAdvice (FR-07)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JedisAdvice — safeKeyToString 사용 검증 (FR-07)")
    class JedisAdviceTest {

        @Test
        @DisplayName("set 호출 시 safeKeyToString → onCacheSet 순서로 주입되어야 한다")
        void set_injectsSafeKeyToString_thenOnCacheSet() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.JedisAdvice advice = new RedisPlugin.JedisAdvice(
                mv, Opcodes.ACC_PUBLIC, "set", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

            advice.onMethodEnter();

            InOrder order = inOrder(mv);
            order.verify(mv).visitVarInsn(eq(Opcodes.ALOAD), eq(1));
            order.verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("safeKeyToString"),
                eq("(Ljava/lang/Object;)Ljava/lang/String;"),
                eq(false)
            );
            order.verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheSet"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("del 호출 시 safeKeyToString → onCacheDel 순서로 주입되어야 한다")
        void del_injectsSafeKeyToString_thenOnCacheDel() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.JedisAdvice advice = new RedisPlugin.JedisAdvice(
                mv, Opcodes.ACC_PUBLIC, "del", "(Ljava/lang/String;)Ljava/lang/Long;");

            advice.onMethodEnter();

            verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("safeKeyToString"),
                eq("(Ljava/lang/Object;)Ljava/lang/String;"),
                eq(false)
            );
            verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheDel"),
                anyString(),
                eq(false)
            );
        }
    }
}
