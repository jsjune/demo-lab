package org.example.agent.testutil;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

/**
 * Utility for restoring mutated global state in tests.
 * Supports system properties and static properties-field snapshots.
 */
public final class TestStateGuard implements AutoCloseable {
    private final Deque<Runnable> cleanups = new ArrayDeque<>();

    public void setSystemProperty(String key, String value) {
        boolean existed = System.getProperties().containsKey(key);
        String previous = System.getProperty(key);
        cleanups.push(() -> restoreSystemProperty(key, existed, previous));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    public void snapshotPropertiesField(Class<?> owner, String fieldName) throws Exception {
        Properties target = getPropertiesField(owner, fieldName);
        Properties snapshot = new Properties();
        snapshot.putAll(target);

        cleanups.push(() -> {
            target.clear();
            target.putAll(snapshot);
        });
    }

    public void setPropertiesFieldValue(Class<?> owner, String fieldName, String key, String value) throws Exception {
        getPropertiesField(owner, fieldName).setProperty(key, value);
    }

    @Override
    public void close() {
        while (!cleanups.isEmpty()) {
            cleanups.pop().run();
        }
    }

    private void restoreSystemProperty(String key, boolean existed, String previous) {
        if (!existed) {
            System.clearProperty(key);
            return;
        }
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private Properties getPropertiesField(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Properties) field.get(null);
    }
}
