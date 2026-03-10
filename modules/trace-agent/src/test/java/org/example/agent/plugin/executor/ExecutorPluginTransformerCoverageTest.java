package org.example.agent.plugin.executor;

import org.example.agent.core.ContextCapturingRunnable;
import org.example.agent.core.TraceRuntime;
import org.example.agent.instrumentation.ByteBuddyIntegrationTest;
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
    void asyncErrorAdvice_callsOnAsyncError() {
        try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
            Throwable err = new RuntimeException("async fail");
            ExecutorPlugin.AsyncErrorAdvice.enter(err);
            rt.verify(() -> TraceRuntime.onAsyncError(eq(err)), times(1));
        }
    }

    // -----------------------------------------------------------------------
    // Helper stubs
    // -----------------------------------------------------------------------

    public static class DummyExecutor {
        public Runnable captured;
        public void execute(Runnable r) { this.captured = r; }
    }
}
