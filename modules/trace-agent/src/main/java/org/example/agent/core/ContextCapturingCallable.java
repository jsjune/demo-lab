package org.example.agent.core;

import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.context.TxIdHolder;
import org.example.agent.core.util.AgentLogger;
import java.util.concurrent.Callable;

/**
 * Wraps a {@link Callable} to capture and restore trace context.
 * Enhanced with lifecycle logging and ASYNC event recording.
 */
public class ContextCapturingCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final String capturedTxId;
    private final String capturedSpanId;

    public ContextCapturingCallable(Callable<V> delegate) {
        this.delegate = delegate;
        this.capturedTxId = TxIdHolder.get();
        this.capturedSpanId = SpanIdHolder.get();
        if (capturedTxId != null) {
            AgentLogger.debug("[ASYNC] Captured context for Callable: txId=" + capturedTxId);
        }
    }

    @Override
    public V call() throws Exception {
        if (capturedTxId == null) {
            return delegate.call();
        }

        // Restore context to the new thread
        TxIdHolder.set(capturedTxId);
        SpanIdHolder.set(capturedSpanId);
        
        long startTime = System.currentTimeMillis();
        String asyncSpanId = TraceRuntime.onAsyncStart("Async-Callable");

        try {
            return delegate.call();
        } catch (Throwable t) {
            TraceRuntime.onAsyncError(t);
            if (t instanceof Exception) throw (Exception) t;
            if (t instanceof Error) throw (Error) t;
            throw new RuntimeException(t);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            TraceRuntime.onAsyncEnd("Async-Callable", asyncSpanId, duration);
            
            // Clean up to prevent thread pollution in pool
            TxIdHolder.clear();
            SpanIdHolder.clear();
        }
    }
}
