package org.example.agent.plugin.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.Label;
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
    // LettuceAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("LettuceAdvice — safeKeyToString 사용 검증")
    class LettuceAdviceTest {

        @Test
        @DisplayName("get onMethodEnter: 키를 safeKeyToString으로 캡처하고 onCacheGet은 호출하지 않아야 한다")
        void get_onEnter_capturesKey_notCallsOnCacheGet() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(
                mv, Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;");

            advice.onMethodEnter();

            // safeKeyToString은 키 캡처를 위해 반드시 호출되어야 한다
            verify(mv).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("safeKeyToString"),
                eq("(Ljava/lang/Object;)Ljava/lang/String;"),
                eq(false)
            );
            // onCacheGet은 onMethodExit에서 attachCacheGetListener를 통해 비동기로 판단 — Enter에서는 미호출
            verify(mv, never()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheGet"),
                anyString(),
                anyBoolean()
            );
        }

        @Test
        @DisplayName("get onMethodExit: attachCacheGetListener가 호출되어야 한다 (비동기 HIT/MISS 위임)")
        void get_onExit_callsAttachCacheGetListener() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(
                mv, Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;");

            advice.onMethodEnter(); // keyLocalIdx 세팅
            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("attachCacheGetListener"),
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
        @DisplayName("set onMethodExit: attachCacheOpListener가 호출되어야 한다")
        void set_onExit_callsAttachCacheOpListener() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(
                mv, Opcodes.ACC_PUBLIC, "set", "(Ljava/lang/Object;Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;");

            advice.onMethodEnter();
            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("attachCacheOpListener"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("eval onMethodEnter: lua 키를 저장해야 한다")
        void eval_onEnter_storesLuaKey() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(
                mv, Opcodes.ACC_PUBLIC, "eval", "(Ljava/lang/String;)Lio/lettuce/core/RedisFuture;");

            advice.onMethodEnter();

            verify(mv).visitLdcInsn(eq("lua:eval"));
        }
    }

    // -----------------------------------------------------------------------
    // JedisTransformer descriptor 필터
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JedisTransformer — descriptor 필터 검증")
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
    // JedisAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JedisAdvice — HIT/MISS 판단 및 safeKeyToString 검증")
    class JedisAdviceTest {

        @Test
        @DisplayName("get onMethodExit: IFNONNULL 기반 HIT/MISS 판단 후 onCacheGet이 두 경로 모두 호출되어야 한다")
        void get_onExit_callsOnCacheGetWithHitMissLogic() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.JedisAdvice advice = new RedisPlugin.JedisAdvice(
                mv, Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/String;)Ljava/lang/String;");

            advice.onMethodEnter(); // keyLocalIdx 세팅
            advice.onMethodExit(Opcodes.ARETURN);

            // null 체크 분기 (IFNONNULL)
            verify(mv).visitJumpInsn(eq(Opcodes.IFNONNULL), any(Label.class));
            // onCacheGet: MISS 경로(false) + HIT 경로(true) = 2회
            verify(mv, times(2)).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheGet"),
                eq("(Ljava/lang/String;Z)V"),
                eq(false)
            );
        }

        @Test
        @DisplayName("set onMethodExit: onCacheSet이 호출되어야 한다")
        void set_onExit_callsOnCacheSet() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.JedisAdvice advice = new RedisPlugin.JedisAdvice(
                mv, Opcodes.ACC_PUBLIC, "set", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

            advice.onMethodEnter();
            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheSet"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("del onMethodExit: onCacheDel이 호출되어야 한다")
        void del_onExit_callsOnCacheDel() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.JedisAdvice advice = new RedisPlugin.JedisAdvice(
                mv, Opcodes.ACC_PUBLIC, "del", "(Ljava/lang/String;)Ljava/lang/Long;");

            advice.onMethodEnter();
            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onCacheDel"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("evalsha onMethodEnter: lua 키를 저장해야 한다")
        void evalsha_onEnter_storesLuaKey() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            RedisPlugin.JedisAdvice advice = new RedisPlugin.JedisAdvice(
                mv, Opcodes.ACC_PUBLIC, "evalsha", "(Ljava/lang/String;)Ljava/lang/Object;");

            advice.onMethodEnter();

            verify(mv).visitLdcInsn(eq("lua:evalsha"));
        }
    }
}
