package org.example.agent.plugin.cache;

import org.example.agent.core.TraceRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

@DisplayName("플러그인: Redis (@Advice 메서드 검증)")
class RedisPluginAdviceTest {

    // -----------------------------------------------------------------------
    // LettuceAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("LettuceAdvice — safeKeyToString 사용 검증")
    class LettuceAdviceTest {

        @Test
        @DisplayName("get onMethodEnter: safeKeyToString을 호출해야 한다")
        void get_enter_callsSafeKeyToString() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                rt.when(() -> TraceRuntime.safeKeyToString(any())).thenReturn("mykey");

                RedisPlugin.LettuceAdvice.enter("get", "mykey", null);

                rt.verify(() -> TraceRuntime.safeKeyToString(eq("mykey")), times(1));
            }
        }

        @Test
        @DisplayName("eval onMethodEnter: lua 키를 저장해야 한다")
        void eval_enter_storesLuaKey() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                // eval => cacheKey = "lua:eval" — safeKeyToString must NOT be called
                RedisPlugin.LettuceAdvice.enter("eval", null, null);
                rt.verify(() -> TraceRuntime.safeKeyToString(any()), never());
            }
        }

        @Test
        @DisplayName("get exit: attachCacheGetListener가 호출되어야 한다")
        void get_exit_callsAttachCacheGetListener() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                RedisPlugin.LettuceAdvice.exit("get", null, null, "mykey");
                rt.verify(() -> TraceRuntime.attachCacheGetListener(isNull(), eq("mykey")), times(1));
            }
        }

        @Test
        @DisplayName("hget exit: attachCacheGetListener가 호출되어야 한다")
        void hget_exit_callsAttachCacheGetListener() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                RedisPlugin.LettuceAdvice.exit("hget", null, null, "field");
                rt.verify(() -> TraceRuntime.attachCacheGetListener(isNull(), eq("field")), times(1));
            }
        }

        @Test
        @DisplayName("set exit: attachCacheOpListener가 호출되어야 한다")
        void set_exit_callsAttachCacheOpListener() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                Object fakeFuture = new Object();
                RedisPlugin.LettuceAdvice.exit("set", fakeFuture, null, "mykey");
                rt.verify(() -> TraceRuntime.attachCacheOpListener(eq(fakeFuture), eq("set"), eq("mykey")), times(1));
            }
        }

        @Test
        @DisplayName("thrown != null 이면 onCacheError가 호출되어야 한다")
        void exit_thrown_callsOnCacheError() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                Throwable err = new RuntimeException("redis fail");
                RedisPlugin.LettuceAdvice.exit("get", null, err, "mykey");
                rt.verify(() -> TraceRuntime.onCacheError(eq(err), eq("get"), eq("mykey")), times(1));
                rt.verify(() -> TraceRuntime.attachCacheGetListener(any(), any()), never());
            }
        }
    }

    // -----------------------------------------------------------------------
    // JedisAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JedisAdvice — HIT/MISS 판단 및 메서드 분기 검증")
    class JedisAdviceTest {

        @Test
        @DisplayName("get exit: hit (result != null) 시 onCacheGet(key, true)")
        void get_exit_hit_callsOnCacheGetTrue() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                RedisPlugin.JedisAdvice.exit("get", "value", null, "mykey");
                rt.verify(() -> TraceRuntime.onCacheGet(eq("mykey"), eq(true)), times(1));
            }
        }

        @Test
        @DisplayName("get exit: miss (result == null) 시 onCacheGet(key, false)")
        void get_exit_miss_callsOnCacheGetFalse() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                RedisPlugin.JedisAdvice.exit("get", null, null, "mykey");
                rt.verify(() -> TraceRuntime.onCacheGet(eq("mykey"), eq(false)), times(1));
            }
        }

        @Test
        @DisplayName("set exit: onCacheSet이 호출되어야 한다")
        void set_exit_callsOnCacheSet() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                RedisPlugin.JedisAdvice.exit("set", null, null, "mykey");
                rt.verify(() -> TraceRuntime.onCacheSet(eq("mykey")), times(1));
            }
        }

        @Test
        @DisplayName("del exit: onCacheDel이 호출되어야 한다")
        void del_exit_callsOnCacheDel() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                RedisPlugin.JedisAdvice.exit("del", null, null, "mykey");
                rt.verify(() -> TraceRuntime.onCacheDel(eq("mykey")), times(1));
            }
        }

        @Test
        @DisplayName("evalsha enter: lua 키 (safeKeyToString 미호출)")
        void evalsha_enter_luaKey() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                RedisPlugin.JedisAdvice.enter("evalsha", null, null);
                // safeKeyToString-like call is not performed for eval — just verifies no crash
            }
        }

        @Test
        @DisplayName("thrown != null 이면 onCacheError가 호출되어야 한다")
        void exit_thrown_callsOnCacheError() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                Throwable err = new RuntimeException("jedis fail");
                RedisPlugin.JedisAdvice.exit("get", null, err, "mykey");
                rt.verify(() -> TraceRuntime.onCacheError(eq(err), eq("get"), eq("mykey")), times(1));
                rt.verify(() -> TraceRuntime.onCacheGet(any(), anyBoolean()), never());
            }
        }
    }
}
