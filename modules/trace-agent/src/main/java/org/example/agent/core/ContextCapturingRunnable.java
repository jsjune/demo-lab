package org.example.agent.core;

/**
 * Wraps a {@link Runnable} to capture and restore trace context.
 * Enhanced with lifecycle logging and ASYNC event recording.
 */
public class ContextCapturingRunnable implements Runnable {
    private final Runnable delegate;
    private final String capturedTxId;
    private final String capturedSpanId;

    public ContextCapturingRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.capturedTxId = TxIdHolder.get();
        this.capturedSpanId = SpanIdHolder.get();
        if (capturedTxId != null) {
            AgentLogger.debug("[ASYNC] Captured context for Runnable: txId=" + capturedTxId);
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
        String asyncSpanId = TraceRuntime.onAsyncStart("Async-Runnable");
        
        try {
            delegate.run();
        } catch (Throwable t) {
            TraceRuntime.onAsyncError(t);
            throw t;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            TraceRuntime.onAsyncEnd("Async-Runnable", asyncSpanId, duration);
            
            // Clean up to prevent thread pollution in pool
            TxIdHolder.clear();
            SpanIdHolder.clear();
        }
    }
}
