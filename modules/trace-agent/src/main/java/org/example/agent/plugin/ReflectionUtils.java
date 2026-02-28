package org.example.agent.plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection helpers shared by all plugins.
 * Fields and methods are cached per class to avoid repeated lookups.
 */
public class ReflectionUtils {

    private static final ConcurrentHashMap<String, Optional<Field>> fieldCache =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Optional<Method>> methodCache =
        new ConcurrentHashMap<>();

    /**
     * Try each field name in order (including superclasses). Returns the value of the first
     * field found, or empty if none match or access fails.
     */
    public static Optional<Object> getFieldValue(Object obj, String... fieldNames) {
        if (obj == null) return Optional.empty();
        for (String name : fieldNames) {
            String key = obj.getClass().getName() + "#" + name;
            Optional<Field> field = fieldCache.computeIfAbsent(key,
                k -> findField(obj.getClass(), name));
            if (field.isPresent()) {
                try {
                    return Optional.ofNullable(field.get().get(obj));
                } catch (Exception ignored) {}
            }
        }
        return Optional.empty();
    }

    /**
     * Find a public method by name and invoke it with the given arguments.
     * Argument types are matched by assignability (not exact type).
     */
    public static Optional<Object> invokeMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return Optional.empty();
        String key = buildMethodKey(obj.getClass(), methodName, args);
        Optional<Method> method = methodCache.computeIfAbsent(key,
            k -> findMethod(obj.getClass(), methodName, args));
        if (method.isPresent()) {
            try {
                Method m = method.get();
                if (!m.canAccess(obj)) {
                    m.setAccessible(true);
                }
                return Optional.ofNullable(m.invoke(obj, args));
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    private static Optional<Field> findField(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> findMethod(Class<?> clazz, String name, Object[] args) {
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            boolean compatible = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && !m.getParameterTypes()[i].isAssignableFrom(args[i].getClass())) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return Optional.of(m);
        }
        return Optional.empty();
    }

    private static String buildMethodKey(Class<?> clazz, String name, Object[] args) {
        StringBuilder sb = new StringBuilder(clazz.getName()).append('#').append(name);
        for (Object a : args) sb.append('#').append(a == null ? "null" : a.getClass().getName());
        return sb.toString();
    }
}
