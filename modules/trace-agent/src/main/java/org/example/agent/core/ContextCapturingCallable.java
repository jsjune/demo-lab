package org.example.agent.core;

import java.util.concurrent.Callable;

/**
 * Wraps a {@link Callable} to capture and restore trace context.
 * Now enhanced to record ASYNC_START and ASYNC_END events.
 */
public class ContextCapturingCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final String capturedTxId;
    private final String capturedSpanId;

    public ContextCapturingCallable(Callable<V> delegate) {
        this.delegate = delegate;
        this.capturedTxId = TxIdHolder.get();
        this.capturedSpanId = SpanIdHolder.get();
    }

    @Override
    public V call() throws Exception {
        if (capturedTxId == null) {
            return delegate.call();
        }

        TxIdHolder.set(capturedTxId);
        SpanIdHolder.set(capturedSpanId);
        
        long startTime = System.currentTimeMillis();
        String asyncSpanId = TraceRuntime.onAsyncStart("Async-Callable");

        try {
            return delegate.call();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            TraceRuntime.onAsyncEnd("Async-Callable", asyncSpanId, duration);
            
            TxIdHolder.clear();
            SpanIdHolder.clear();
        }
    }
}
