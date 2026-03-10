package org.example.agent.core.emitter;

import org.example.common.TraceEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("코어: EventEmitter 인터페이스")
class EventEmitterTest {

    @Test
    @DisplayName("EventEmitter는 람다로 구현할 수 있다")
    void eventEmitter_isFunctionalInterface() {
        List<Object> captured = new ArrayList<>();
        EventEmitter emitter = event -> captured.add(event);
        emitter.emit(null);
        assertEquals(1, captured.size());
    }

    @Test
    @DisplayName("TcpSenderEmitter.emit()은 TcpSender.send()에 위임한다")
    void tcpSenderEmitter_delegatesToTcpSender() {
        TraceEvent event = mock(TraceEvent.class);
        try (MockedStatic<TcpSender> tcpMock = mockStatic(TcpSender.class)) {
            EventEmitter emitter = new TcpSenderEmitter();
            emitter.emit(event);
            tcpMock.verify(() -> TcpSender.send(event));
        }
    }
}
