package org.example.agent.plugin.http;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AsyncMvcInstrumentationTest {

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
    }

    @Test
    void testAsyncRequestShouldRecordEndEventViaListener() throws Exception {
        try (MockedStatic<TcpSender> tcpSender = mockStatic(TcpSender.class)) {
            // 1. Mock Objects
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            AsyncContext asyncContext = mock(AsyncContext.class);

            when(request.getMethod()).thenReturn("GET");
            when(request.getRequestURI()).thenReturn("/mvc/test");
            when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
            when(request.isAsyncStarted()).thenReturn(true); // 비동기 시작됨
            when(request.getAsyncContext()).thenReturn(asyncContext);

            // 2. Simulate DispatcherServlet.doDispatch Enter
            TraceRuntime.onHttpInStart(request, "GET", "/mvc/test", null, null, true);
            String txId = TxIdHolder.get();
            assertNotNull(txId);

            // 3. Simulate DispatcherServlet.doDispatch Exit (Async Active)
            TraceRuntime.registerAsyncListener(request, "GET", "/mvc/test", System.currentTimeMillis());
            
            // 4. Verify Listener Registration
            ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
            verify(asyncContext).addListener(listenerCaptor.capture());
            AsyncListener registeredListener = listenerCaptor.getValue();

            // 5. Simulate Async Completion (Container calls onComplete)
            when(response.getStatus()).thenReturn(200);
            registeredListener.onComplete(null);

            // 6. Final Verification: Check if HTTP_IN_END event was sent
            ArgumentCaptor<TraceEvent> eventCaptor = ArgumentCaptor.forClass(TraceEvent.class);
            tcpSender.verify(() -> TcpSender.send(eventCaptor.capture()), atLeastOnce());

            List<TraceEvent> sentEvents = eventCaptor.getAllValues();
            boolean hasEndEvent = sentEvents.stream()
                    .anyMatch(e -> e.type() == TraceEventType.HTTP_IN_END && e.txId().equals(txId));
            
            assertTrue(hasEndEvent, "HTTP_IN_END event should be recorded for async request");
        }
    }
}
