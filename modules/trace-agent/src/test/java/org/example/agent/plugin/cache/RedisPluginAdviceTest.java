package org.example.agent.plugin.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@DisplayName("플러그인: Redis (Advice 바이트코드 검증)")
class RedisPluginAdviceTest {

    @Test
    @DisplayName("Lettuce get 메서드 호출 시 onCacheGet이 호출되어야 한다")
    void testLettuceAdviceOnMethodEnter() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        RedisPlugin.LettuceAdvice advice = new RedisPlugin.LettuceAdvice(mv, Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;");

        advice.onMethodEnter();

        // onCacheGet 호출 확인
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onCacheGet"),
            anyString(),
            eq(false)
        );
    }

    @Test
    @DisplayName("Jedis set 메서드 호출 시 onCacheSet이 호출되어야 한다")
    void testJedisAdviceOnMethodEnter() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        RedisPlugin.JedisAdvice advice = new RedisPlugin.JedisAdvice(mv, Opcodes.ACC_PUBLIC, "set", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

        advice.onMethodEnter();

        // onCacheSet 호출 확인
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onCacheSet"),
            anyString(),
            eq(false)
        );
    }
}
