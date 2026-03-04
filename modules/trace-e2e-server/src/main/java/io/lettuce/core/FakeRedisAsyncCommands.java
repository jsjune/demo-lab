package io.lettuce.core;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Test-only fake async Redis commands.
 * Methods/signatures are intentionally shaped to be instrumented by RedisPlugin.
 */
public class FakeRedisAsyncCommands {
    private final Map<String, String> store = new ConcurrentHashMap<>();

    public RedisFuture<String> get(String key) {
        if (key != null && key.startsWith("err:")) {
            throw new IllegalStateException("forced-cache-error");
        }
        return completedFuture(store.get(key), null);
    }

    public RedisFuture<String> set(String key, String value) {
        store.put(key, value);
        return completedFuture("OK", null);
    }

    public RedisFuture<Long> del(String key) {
        return completedFuture(store.remove(key) != null ? 1L : 0L, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> completedFuture(T result, Throwable error) {
        return (RedisFuture<T>) Proxy.newProxyInstance(
            FakeRedisAsyncCommands.class.getClassLoader(),
            new Class[]{RedisFuture.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("whenComplete".equals(name) && args != null && args.length == 1 && args[0] instanceof BiConsumer) {
                    BiConsumer<Object, Throwable> consumer = (BiConsumer<Object, Throwable>) args[0];
                    consumer.accept(result, error);
                    return proxy;
                }
                if ("toString".equals(name)) return "FakeRedisFuture";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args != null && args.length == 1 ? args[0] : null);
                Class<?> rt = method.getReturnType();
                if (rt.equals(boolean.class)) return false;
                if (rt.equals(byte.class)) return (byte) 0;
                if (rt.equals(short.class)) return (short) 0;
                if (rt.equals(int.class)) return 0;
                if (rt.equals(long.class)) return 0L;
                if (rt.equals(float.class)) return 0f;
                if (rt.equals(double.class)) return 0d;
                if (rt.equals(char.class)) return '\0';
                return null;
            }
        );
    }
}
