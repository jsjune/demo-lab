package org.example.agent.core;

import org.example.common.TraceEvent;

/**
 * Abstraction over the event transport layer.
 * Implementations receive fully-built {@link TraceEvent} objects and forward
 * them to the appropriate sink (TCP, gRPC, in-memory, etc.).
 *
 * <p>Called from within handler {@code safeRun()} blocks — implementations
 * must not throw checked exceptions.
 */
@FunctionalInterface
public interface EventEmitter {
    void emit(TraceEvent event);
}
