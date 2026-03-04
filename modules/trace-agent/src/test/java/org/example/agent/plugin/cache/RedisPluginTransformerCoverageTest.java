package org.example.agent.plugin.cache;

import org.example.agent.testutil.AsmTestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedisPluginTransformerCoverageTest {

    @Test
    void lettuceTransformer_transformsRedisAsyncCommands() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "io/lettuce/core/api/async/RedisAsyncCommandsImpl",
            AsmTestUtils.MethodSpec.of("get", "(Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;"),
            AsmTestUtils.MethodSpec.of("set", "(Ljava/lang/Object;Ljava/lang/Object;)Lio/lettuce/core/RedisFuture;"));

        RedisPlugin.LettuceTransformer t = new RedisPlugin.LettuceTransformer();
        byte[] out = t.transform(getClass().getClassLoader(),
            "io/lettuce/core/api/async/RedisAsyncCommandsImpl", null, null, original);

        assertNotNull(out);
    }

    @Test
    void lettuceTransformer_nonTarget_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods("com/example/NoLettuce");
        RedisPlugin.LettuceTransformer t = new RedisPlugin.LettuceTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "com/example/NoLettuce", null, null, original));
    }

    @Test
    void jedisTransformer_transformsStringCommandSignature() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "redis/clients/jedis/Jedis",
            AsmTestUtils.MethodSpec.of("get", "(Ljava/lang/String;)Ljava/lang/String;"),
            AsmTestUtils.MethodSpec.of("set", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));

        RedisPlugin.JedisTransformer t = new RedisPlugin.JedisTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "redis/clients/jedis/Jedis", null, null, original);

        assertNotNull(out);
    }

    @Test
    void jedisTransformer_nonTarget_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods("com/example/NoJedis");
        RedisPlugin.JedisTransformer t = new RedisPlugin.JedisTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "com/example/NoJedis", null, null, original));
    }
}

