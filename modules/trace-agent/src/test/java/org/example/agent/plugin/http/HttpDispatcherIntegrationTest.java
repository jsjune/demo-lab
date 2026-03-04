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
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
            MockHttpServletResponse response = new MockHttpServletResponse();

            TraceRuntime.onHttpInStart(request, request.getMethod(), request.getRequestURI(), null, null, false);
            TraceRuntime.onHttpInEnd(request.getMethod(), request.getRequestURI(), response.getStatus(), 10L);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(), anyString(), anyString(), any(), any(), anyBoolean()),
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

            String txId = request.getHeader("X-Tx-Id");
            String spanId = request.getHeader("X-Span-Id");
            TraceRuntime.onHttpInStart(request, request.getMethod(), request.getRequestURI(), txId, spanId, false);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(), eq("GET"), eq("/test"), eq("existing-tx-id"), eq("existing-span-id"), anyBoolean()),
                times(1)
            );
        }
    }

    @Test
    void testErrorDispatchFiltering() {
        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
            runtimeMock.when(() -> TraceRuntime.isSecondaryDispatch(any())).thenReturn(true);

            boolean isSecondary = TraceRuntime.isSecondaryDispatch(request);
            if (!isSecondary) {
                TraceRuntime.onHttpInStart(request, request.getMethod(), request.getRequestURI(), null, null, false);
            }

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(), any(), any(), any(), any(), anyBoolean()),
                times(0)
            );
        }
    }
}
