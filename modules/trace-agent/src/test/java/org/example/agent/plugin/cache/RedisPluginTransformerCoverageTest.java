package org.example.agent.plugin.cache;

import org.example.agent.core.TraceRuntime;
import org.example.agent.instrumentation.ByteBuddyIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisPluginTransformerCoverageTest extends ByteBuddyIntegrationTest {

    @Test
    void pluginMetadata() {
        RedisPlugin p = new RedisPlugin();
        assertEquals("cache", p.pluginId());
    }

    @Test
    void lettuceAdvice_isAppliedToGet() throws Exception {
        Class<?> cls = instrument(
            DummyLettuceCommands.class,
            RedisPlugin.LettuceAdvice.class,
            named("get").and(takesArguments(1)));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method get = cls.getDeclaredMethod("get", Object.class);

        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            rt.when(() -> TraceRuntime.safeKeyToString(any(Object.class))).thenReturn("my-key");
            get.invoke(instance, "my-key");
            rt.verify(() -> TraceRuntime.attachCacheGetListener(nullable(Object.class), eq("my-key")), atLeastOnce());
        }
    }

    @Test
    void jedisAdvice_isAppliedToGet() throws Exception {
        Class<?> cls = instrument(
            DummyJedis.class,
            RedisPlugin.JedisAdvice.class,
            named("get").and(takesArgument(0, String.class)));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method get = cls.getDeclaredMethod("get", String.class);

        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            get.invoke(instance, "cache-key");
            rt.verify(() -> TraceRuntime.onCacheGet(eq("cache-key"), anyBoolean()), atLeastOnce());
        }
    }

    // -----------------------------------------------------------------------
    // Helper stubs
    // -----------------------------------------------------------------------

    public static class DummyLettuceCommands {
        public Object get(Object key) { return null; }
    }

    public static class DummyJedis {
        public String get(String key) { return "value"; }
    }
}
