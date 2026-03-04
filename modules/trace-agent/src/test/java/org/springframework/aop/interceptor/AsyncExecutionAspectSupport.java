package org.springframework.aop.interceptor;

import java.lang.reflect.Method;

public class AsyncExecutionAspectSupport {
    protected void handleError(Throwable ex, Method method, Object... params) {
        // no-op for instrumentation test
    }
}
