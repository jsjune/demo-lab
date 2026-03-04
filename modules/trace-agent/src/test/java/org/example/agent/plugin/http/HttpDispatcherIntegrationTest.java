package org.example.agent.plugin.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.TraceRuntime;
import org.example.agent.instrumentation.ByteBuddyIntegrationTest;
import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class HttpDispatcherIntegrationTest extends ByteBuddyIntegrationTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
    }

    @AfterEach
    void tearDown() {
        stateGuard.close();
    }

    public static class DummyDispatcherServlet {
        public void doDispatch(HttpServletRequest request, HttpServletResponse response) {
            // no-op: instrumentation should inject TraceRuntime calls around this method
        }
    }

    @Test
    void testDispatcherServletInstrumentation() throws Exception {
        stateGuard.setPropertiesFieldValue(
            AgentConfig.class, "props", "http.dispatcher.class",
            DummyDispatcherServlet.class.getName().replace('.', '/'));
        HttpPlugin plugin = new HttpPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> transformed = transformAndLoad(DummyDispatcherServlet.class, transformer);
        Object instance = transformed.getDeclaredConstructor().newInstance();
        Method doDispatch = transformed.getDeclaredMethod("doDispatch", HttpServletRequest.class, HttpServletResponse.class);
        assertNotNull(doDispatch);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/test");
        when(request.getHeader(AgentConfig.getHeaderKey())).thenReturn(null);
        when(request.getHeader("X-Span-Id")).thenReturn(null);
        when(request.getHeader(AgentConfig.getForceSampleHeader())).thenReturn(null);
        when(request.isAsyncStarted()).thenReturn(false);
        when(response.getStatus()).thenReturn(200);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            runtimeMock.when(() -> TraceRuntime.isSecondaryDispatch(any())).thenReturn(false);
            doDispatch.invoke(instance, request, response);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(eq(request), eq("GET"), eq("/test"), isNull(), isNull(), eq(false)),
                times(1)
            );
            runtimeMock.verify(
                () -> TraceRuntime.onHttpInEnd(eq("GET"), eq("/test"), eq(200), anyLong()),
                times(1)
            );
        }
    }

    @Test
    void testDispatcherServletWithIncomingTxId() throws Exception {
        stateGuard.setPropertiesFieldValue(
            AgentConfig.class, "props", "http.dispatcher.class",
            DummyDispatcherServlet.class.getName().replace('.', '/'));
        HttpPlugin plugin = new HttpPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> transformed = transformAndLoad(DummyDispatcherServlet.class, transformer);
        Object instance = transformed.getDeclaredConstructor().newInstance();
        Method doDispatch = transformed.getDeclaredMethod("doDispatch", HttpServletRequest.class, HttpServletResponse.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/test");
        when(request.getHeader(AgentConfig.getHeaderKey())).thenReturn("existing-tx-id");
        when(request.getHeader("X-Span-Id")).thenReturn("existing-span-id");
        when(request.getHeader(AgentConfig.getForceSampleHeader())).thenReturn(null);
        when(request.isAsyncStarted()).thenReturn(false);
        when(response.getStatus()).thenReturn(200);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            runtimeMock.when(() -> TraceRuntime.isSecondaryDispatch(any())).thenReturn(false);
            doDispatch.invoke(instance, request, response);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(eq(request), eq("GET"), eq("/test"), eq("existing-tx-id"), eq("existing-span-id"), eq(false)),
                times(1)
            );
        }
    }

    @Test
    void testErrorDispatchFiltering() throws Exception {
        stateGuard.setPropertiesFieldValue(
            AgentConfig.class, "props", "http.dispatcher.class",
            DummyDispatcherServlet.class.getName().replace('.', '/'));
        HttpPlugin plugin = new HttpPlugin();
        ClassFileTransformer transformer = plugin.transformers().get(0);
        Class<?> transformed = transformAndLoad(DummyDispatcherServlet.class, transformer);
        Object instance = transformed.getDeclaredConstructor().newInstance();
        Method doDispatch = transformed.getDeclaredMethod("doDispatch", HttpServletRequest.class, HttpServletResponse.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/error");
        when(request.isAsyncStarted()).thenReturn(false);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            runtimeMock.when(() -> TraceRuntime.isSecondaryDispatch(any())).thenReturn(true);
            runtimeMock.when(() -> TraceRuntime.restoreContext(any())).thenAnswer(inv -> null);
            doDispatch.invoke(instance, request, response);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(), any(), any(), any(), any(), anyBoolean()),
                never()
            );
        }
    }

}
