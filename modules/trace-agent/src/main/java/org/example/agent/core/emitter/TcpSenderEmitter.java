package org.example.agent.core.emitter;

import org.example.common.TraceEvent;

/**
 * Default {@link EventEmitter} implementation that forwards events to
 * {@link TcpSender}.
 */
public final class TcpSenderEmitter implements EventEmitter {
    @Override
    public void emit(TraceEvent event) {
        TcpSender.send(event);
    }
}
