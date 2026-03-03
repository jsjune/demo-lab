package org.example.agent.core;

/**
 * Reactor Context key constants for trace propagation.
 * No compile-time Reactor dependency — keys are plain strings used via reflection.
 */
public final class ReactorContextHolder {
    public static final String TX_ID_KEY   = "trace.txId";
    public static final String SPAN_ID_KEY = "trace.spanId";

    private ReactorContextHolder() {}
}
