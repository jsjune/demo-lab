package org.example.agent.core;

/**
 * Wraps a {@link Runnable} to capture and restore trace context.
 * Now enhanced to record ASYNC_START and ASYNC_END events.
 */
public class ContextCapturingRunnable implements Runnable {
    private final Runnable delegate;
    private final String capturedTxId;
    private final String capturedSpanId;

    public ContextCapturingRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.capturedTxId = TxIdHolder.get();
        this.capturedSpanId = SpanIdHolder.get();
    }

    @Override
    public void run() {
        if (capturedTxId == null) {
            delegate.run();
            return;
        }

        TxIdHolder.set(capturedTxId);
        SpanIdHolder.set(capturedSpanId);
        
        long startTime = System.currentTimeMillis();
        // Fire ASYNC_START and get the new spanId for this thread's scope
        String asyncSpanId = TraceRuntime.onAsyncStart("Async-Runnable");
        
        try {
            delegate.run();
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            TraceRuntime.onAsyncEnd("Async-Runnable", asyncSpanId, duration);
            
            TxIdHolder.clear();
            SpanIdHolder.clear();
        }
    }
}
