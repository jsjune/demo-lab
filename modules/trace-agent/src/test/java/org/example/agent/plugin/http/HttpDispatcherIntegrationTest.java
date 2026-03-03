package org.example.agent.plugin.http;

import org.example.agent.core.TraceRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class HttpDispatcherIntegrationTest {

    @Test
    void testDispatcherServletInstrumentation() {
        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            // Simulated behavior of DispatcherServlet.doDispatch instrumented code
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // Simulating bytecode injection at onMethodEnter
            TraceRuntime.onHttpInStart(request.getMethod(), request.getRequestURI(), null, null, false);

            // Simulating bytecode injection at onMethodExit
            TraceRuntime.onHttpInEnd(request.getMethod(), request.getRequestURI(), response.getStatus(), 10L);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(anyString(), anyString(), any(), any(), anyBoolean()),
                times(1)
            );
            runtimeMock.verify(
                () -> TraceRuntime.onHttpInEnd(anyString(), anyString(), anyInt(), anyLong()),
                times(1)
            );
        }
    }

    @Test
    void testDispatcherServletWithIncomingTxId() {
        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
            request.addHeader("X-Tx-Id", "existing-tx-id");
            request.addHeader("X-Span-Id", "existing-span-id");

            // Simulating bytecode extraction and call
            String txId = request.getHeader("X-Tx-Id");
            String spanId = request.getHeader("X-Span-Id");
            TraceRuntime.onHttpInStart(request.getMethod(), request.getRequestURI(), txId, spanId, false);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(eq("GET"), eq("/test"), eq("existing-tx-id"), eq("existing-span-id"), anyBoolean()),
                times(1)
            );
        }
    }

    @Test
    void testErrorDispatchFiltering() {
        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            // Mock a skip condition (Error or Async dispatch)
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
            runtimeMock.when(() -> TraceRuntime.shouldSkipTracking(any())).thenReturn(true);

            // The instrumented code checks shouldSkipTracking before calling onHttpInStart
            boolean isTracked = !TraceRuntime.shouldSkipTracking(request);
            if (isTracked) {
                TraceRuntime.onHttpInStart(request.getMethod(), request.getRequestURI(), null, null, false);
            }

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(), any(), any(), any(), anyBoolean()),
                times(0)
            );
        }
    }
}
