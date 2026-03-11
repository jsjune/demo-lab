package org.example.agent.core.context;

/**
 * ThreadLocal holder for the current @Async method name.
 *
 * <p>Set by ExecutorPlugin.AsyncDetermineExecutorAdvice on the calling thread when
 * AsyncExecutionAspectSupport.determineAsyncExecutor(Method) is intercepted,
 * then consumed (read+cleared) by ContextCapturingRunnable constructor on the same thread.
 */
public final class AsyncTaskNameHolder {
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private AsyncTaskNameHolder() {}

    public static void set(String name)  { HOLDER.set(name); }
    public static String get()           { return HOLDER.get(); }
    public static String getAndClear()   { String v = HOLDER.get(); HOLDER.remove(); return v; }
    public static void clear()           { HOLDER.remove(); }
}
