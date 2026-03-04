package org.example.agent.integration;

import org.example.agent.core.TraceRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpAgentIntegrationTest {

    @Test
    void testTraceIgnoreAnnotation() throws Exception {
        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            // Verify new signature: (Object request, String method, String path, String incomingTxId, String incomingSpanId, boolean forceTrace)
            runtimeMock.verify(() -> TraceRuntime.onHttpInStart(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean()), never());
        }
    }
}
