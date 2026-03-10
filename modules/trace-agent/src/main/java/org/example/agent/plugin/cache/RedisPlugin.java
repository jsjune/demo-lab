package org.example.agent.plugin.cache;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.example.agent.AgentInitializer;
import org.example.agent.TracerPlugin;
import org.example.agent.core.TraceRuntime;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * RedisPlugin: instruments Lettuce async commands and Jedis sync commands for cache tracing.
 *
 * <p>Uses ByteBuddy @Advice inline instrumentation — no raw ASM required.
 */
public class RedisPlugin implements TracerPlugin {

    @Override public String pluginId() { return "cache"; }

    @Override
    public AgentBuilder install(AgentBuilder builder) {
        if (!isEnabled(null)) return builder;

        ClassFileLocator agentLocator = AgentInitializer.getAgentLocator();

        return builder
            // Lettuce async commands
            .type(nameStartsWith("io.lettuce.core.").and(nameContains("RedisAsyncCommands")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(LettuceAdvice.class, agentLocator)
                    .on(namedOneOf("get", "set", "del", "hget", "hset", "eval", "evalsha")
                        .and(returns(named("io.lettuce.core.RedisFuture"))))))
            // Jedis sync commands
            .type(nameStartsWith("redis.clients.jedis.").and(nameContains("Jedis")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(JedisAdvice.class, agentLocator)
                    .on(namedOneOf("get", "set", "del", "eval", "evalsha")
                        .and(takesArgument(0, String.class)))));
    }

    // -----------------------------------------------------------------------
    // Lettuce Advice
    // -----------------------------------------------------------------------

    public static class LettuceAdvice {

        @Advice.OnMethodEnter
        static void enter(
            @Advice.Origin("#m") String methodName,
            @Advice.Argument(value = 0, optional = true) Object key,
            @Advice.Local("cacheKey") String cacheKey
        ) {
            if ("eval".equals(methodName) || "evalsha".equals(methodName)) {
                cacheKey = "lua:" + methodName;
            } else {
                cacheKey = TraceRuntime.safeKeyToString(key);
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Origin("#m") String methodName,
            @Advice.Return Object future,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("cacheKey") String cacheKey
        ) {
            if (thrown != null) {
                TraceRuntime.onCacheError(thrown, methodName, cacheKey);
                return;
            }
            if ("get".equals(methodName) || "hget".equals(methodName)) {
                TraceRuntime.attachCacheGetListener(future, cacheKey);
            } else {
                TraceRuntime.attachCacheOpListener(future, methodName, cacheKey);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Jedis Advice
    // -----------------------------------------------------------------------

    public static class JedisAdvice {

        @Advice.OnMethodEnter
        static void enter(
            @Advice.Origin("#m") String methodName,
            @Advice.Argument(value = 0, optional = true) Object key,
            @Advice.Local("cacheKey") String cacheKey
        ) {
            if ("eval".equals(methodName) || "evalsha".equals(methodName)) {
                cacheKey = "lua:" + methodName;
            } else {
                cacheKey = key != null ? key.toString() : "null";
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Origin("#m") String methodName,
            @Advice.Return Object result,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("cacheKey") String cacheKey
        ) {
            if (thrown != null) {
                TraceRuntime.onCacheError(thrown, methodName, cacheKey);
                return;
            }
            if ("get".equals(methodName)) {
                TraceRuntime.onCacheGet(cacheKey, result != null);
            } else if ("del".equals(methodName)) {
                TraceRuntime.onCacheDel(cacheKey);
            } else {
                TraceRuntime.onCacheSet(cacheKey);
            }
        }
    }
}
