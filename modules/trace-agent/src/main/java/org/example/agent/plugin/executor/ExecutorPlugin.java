package org.example.agent.plugin.executor;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.example.agent.AgentInitializer;
import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.ContextCapturingCallable;
import org.example.agent.core.ContextCapturingRunnable;
import org.example.agent.core.TraceRuntime;

import java.util.concurrent.Callable;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * ExecutorPlugin: instruments ThreadPoolExecutor, ScheduledThreadPoolExecutor,
 * ForkJoinPool, and Spring's AsyncExecutionAspectSupport for context propagation.
 *
 * <p>Uses ByteBuddy @Advice inline instrumentation — no raw ASM required.
 *
 * <p>Design note — Callable intentionally NOT instrumented:
 * {@code AbstractExecutorService.submit(Callable)} wraps the Callable into a {@code FutureTask}
 * and internally calls {@code execute(FutureTask)}, which is already caught by
 * {@code RunnableWrappingAdvice}. Instrumenting submit(Callable) separately would cause
 * double ASYNC_START emission (once from ContextCapturingRunnable, once from ContextCapturingCallable).
 */
public class ExecutorPlugin implements TracerPlugin {

    @Override public String pluginId() { return "executor"; }
    @Override public boolean requiresBootstrapSearch() { return true; }

    @Override
    public AgentBuilder install(AgentBuilder builder) {
        // Respect per-plugin enabled flag from AgentConfig
        if (!isEnabled()) return builder;

        ClassFileLocator agentLocator = AgentInitializer.getAgentLocator();

        return builder
            // ThreadPoolExecutor.execute(Runnable) — wraps Runnable with context
            .type(named("java.util.concurrent.ThreadPoolExecutor"))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(RunnableWrappingAdvice.class, agentLocator)
                    .on(named("execute").and(takesArgument(0, Runnable.class)))))
            // ScheduledThreadPoolExecutor.execute(Runnable)
            .type(named("java.util.concurrent.ScheduledThreadPoolExecutor"))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(RunnableWrappingAdvice.class, agentLocator)
                    .on(named("execute").and(takesArgument(0, Runnable.class)))))
            // ForkJoinPool: external Runnable submissions (CompletableFuture path)
            .type(named("java.util.concurrent.ForkJoinPool"))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(RunnableWrappingAdvice.class, agentLocator)
                    .on((named("execute").or(nameStartsWith("external")))
                        .and(takesArgument(0, Runnable.class)))))
            // Spring @Async error handler
            .type(named("org.springframework.aop.interceptor.AsyncExecutionAspectSupport"))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(AsyncErrorAdvice.class, agentLocator)
                    .on(named("handleError").and(takesArgument(0, Throwable.class)))));
    }

    // -----------------------------------------------------------------------
    // Advice classes (static methods inlined into target class by ByteBuddy)
    // -----------------------------------------------------------------------

    /**
     * Replaces the Runnable argument with a ContextCapturingRunnable so that
     * TxId and SpanId are propagated into the async thread.
     */
    public static class RunnableWrappingAdvice {
        @Advice.OnMethodEnter
        static void enter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Runnable runnable
        ) {
            if (runnable != null && !(runnable instanceof ContextCapturingRunnable)) {
                runnable = new ContextCapturingRunnable(runnable);
            }
        }
    }

    /**
     * Replaces the Callable argument with a ContextCapturingCallable so that
     * TxId and SpanId are propagated into the async thread.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static class CallableWrappingAdvice {
        @Advice.OnMethodEnter
        static void enter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Callable callable
        ) {
            if (callable != null && !(callable instanceof ContextCapturingCallable)) {
                callable = new ContextCapturingCallable<>(callable);
            }
        }
    }

    /**
     * Intercepts Spring's AsyncExecutionAspectSupport.handleError() to record
     * async execution errors in the trace.
     */
    public static class AsyncErrorAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Throwable throwable) {
            TraceRuntime.onAsyncError(throwable);
        }
    }
}
