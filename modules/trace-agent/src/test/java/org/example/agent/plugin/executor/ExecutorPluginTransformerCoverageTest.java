package org.example.agent.plugin.executor;

import org.example.agent.core.ContextCapturingCallable;
import org.example.agent.core.ContextCapturingRunnable;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.context.AsyncTaskNameHolder;
import org.example.agent.instrumentation.ByteBuddyIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExecutorPluginTransformerCoverageTest extends ByteBuddyIntegrationTest {

    @Test
    void pluginMetadata() {
        ExecutorPlugin p = new ExecutorPlugin();
        assertEquals("executor", p.pluginId());
        assertTrue(p.requiresBootstrapSearch());
    }

    @Test
    void runnableWrappingAdvice_wrapsRunnableBeforeMethodBody() throws Exception {
        Class<?> cls = instrument(
            DummyExecutor.class,
            ExecutorPlugin.RunnableWrappingAdvice.class,
            named("execute").and(takesArgument(0, Runnable.class)));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method execute = cls.getDeclaredMethod("execute", Runnable.class);

        Runnable original = () -> {};
        execute.invoke(instance, original);

        Field captured = cls.getDeclaredField("captured");
        captured.setAccessible(true);
        Object capturedRunnable = captured.get(instance);

        assertInstanceOf(ContextCapturingRunnable.class, capturedRunnable);
    }

    @Test
    void callableWrappingAdvice_wrapsCallableBeforeMethodBody() throws Exception {
        Class<?> cls = instrument(
            DummySubmitter.class,
            ExecutorPlugin.CallableWrappingAdvice.class,
            named("submit").and(takesArgument(0, java.util.concurrent.Callable.class)));

        Object instance = cls.getDeclaredConstructor().newInstance();
        Method submit = cls.getDeclaredMethod("submit", java.util.concurrent.Callable.class);

        java.util.concurrent.Callable<String> original = () -> "result";
        submit.invoke(instance, original);

        Field captured = cls.getDeclaredField("captured");
        captured.setAccessible(true);
        Object capturedCallable = captured.get(instance);

        assertInstanceOf(ContextCapturingCallable.class, capturedCallable);
    }

    @AfterEach
    void clearHolder() {
        AsyncTaskNameHolder.clear();
    }

    @Test
    void asyncErrorAdvice_callsOnAsyncError() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            Throwable err = new RuntimeException("async fail");
            ExecutorPlugin.AsyncErrorAdvice.enter(err);
            rt.verify(() -> TraceRuntime.onAsyncError(eq(err)), times(1));
        }
    }

    @Test
    void asyncDetermineExecutorAdvice_enter_setsTaskName() throws Exception {
        Method method = DummyAsyncService.class.getDeclaredMethod("processOrder");

        ExecutorPlugin.AsyncDetermineExecutorAdvice.enter(method);

        assertEquals("DummyAsyncService.processOrder", AsyncTaskNameHolder.get());
    }

    @Test
    void asyncDetermineExecutorAdvice_enter_ignoresNonMethod_doesNotThrow() {
        assertDoesNotThrow(() -> ExecutorPlugin.AsyncDetermineExecutorAdvice.enter("not-a-method"));
        assertNull(AsyncTaskNameHolder.get());
    }

    @Test
    void contextCapturingRunnable_consumesTaskNameFromHolder() throws Exception {
        AsyncTaskNameHolder.set("DummyAsyncService.processOrder");

        ContextCapturingRunnable wrapper = new ContextCapturingRunnable(() -> {});

        // Holder must be cleared (consumed) after constructor
        assertNull(AsyncTaskNameHolder.get(), "getAndClear() must clear the holder");
    }

    // -----------------------------------------------------------------------
    // Helper stubs
    // -----------------------------------------------------------------------

    public static class DummyExecutor {
        public Runnable captured;
        public void execute(Runnable r) { this.captured = r; }
    }

    public static class DummySubmitter {
        public java.util.concurrent.Callable<?> captured;
        public <T> void submit(java.util.concurrent.Callable<T> c) { this.captured = c; }
    }

    public static class DummyAsyncService {
        public void processOrder() {}
    }
}
