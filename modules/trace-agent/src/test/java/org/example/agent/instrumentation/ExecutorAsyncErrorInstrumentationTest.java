package org.example.agent.instrumentation;

import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.executor.ExecutorPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class ExecutorAsyncErrorInstrumentationTest extends ByteBuddyIntegrationTest {

    @Test
    void asyncExecutionAspectHandleError_shouldCallTraceRuntimeOnAsyncError() throws Exception {
        ExecutorPlugin plugin = new ExecutorPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(1);

        Class<?> transformed = transformAndLoad(
            org.springframework.aop.interceptor.AsyncExecutionAspectSupport.class,
            transformer);
        Constructor<?> ctor = transformed.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Object instance = ctor.newInstance(args);
        Method handleError = transformed.getDeclaredMethod(
            "handleError", Throwable.class, Method.class, Object[].class);
        handleError.setAccessible(true);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            handleError.invoke(instance, new RuntimeException("boom"), null, null);
            runtimeMock.verify(() -> TraceRuntime.onAsyncError(any(Throwable.class)), times(1));
        }
    }
}
