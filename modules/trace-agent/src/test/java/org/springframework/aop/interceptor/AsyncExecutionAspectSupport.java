package org.springframework.aop.interceptor;

import java.lang.reflect.Method;

/**
 * Minimal test stub for executor instrumentation tests.
 * Keeps constructor and handleError signature simple and deterministic.
 */
public class AsyncExecutionAspectSupport {
    public AsyncExecutionAspectSupport() {
    }

    @SuppressWarnings("unused")
    protected void handleError(Throwable ex, Method method, Object... params) {
        // no-op
    }
}
