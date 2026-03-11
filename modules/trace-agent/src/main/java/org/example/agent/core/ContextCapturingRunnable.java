package org.example.agent.core;

import org.example.agent.core.context.AsyncTaskNameHolder;
import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.context.TxIdHolder;
import org.example.agent.core.util.AgentLogger;

/**
 * Wraps a {@link Runnable} to capture and restore trace context.
 * Enhanced with lifecycle logging and ASYNC event recording.
 *
 * <p>taskName is read from {@link AsyncTaskNameHolder} (set by ExecutorPlugin.AsyncDetermineExecutorAdvice
 * on the calling thread just before execute() is called). Falls back to taskName if not set.
 */
public class ContextCapturingRunnable implements Runnable {
    private final Runnable delegate;
    private final String capturedTxId;
    private final String capturedSpanId;
    private final String taskName;

    public ContextCapturingRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.capturedTxId = TxIdHolder.get();
        this.capturedSpanId = SpanIdHolder.get();
        String name = AsyncTaskNameHolder.getAndClear();
        this.taskName = (name != null) ? name : "Async-Runnable";
        if (capturedTxId != null) {
            AgentLogger.debug("[ASYNC] Captured context for Runnable: txId=" + capturedTxId + " task=" + taskName);
        }
    }

    @Override
    public void run() {
        if (capturedTxId == null) {
            delegate.run();
            return;
        }

        // Restore context to the new thread
        TxIdHolder.set(capturedTxId);
        SpanIdHolder.set(capturedSpanId);
        
        long startTime = System.currentTimeMillis();
        // Record ASYNC_START and get the thread-local span ID for this task
        String asyncSpanId = TraceRuntime.onAsyncStart(taskName);
        
        try {
            delegate.run();
        } catch (Throwable t) {
            TraceRuntime.onAsyncError(t);
            throw t;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            TraceRuntime.onAsyncEnd(taskName, asyncSpanId, duration);
            
            // Clean up to prevent thread pollution in pool
            TxIdHolder.clear();
            SpanIdHolder.clear();
        }
    }
}
