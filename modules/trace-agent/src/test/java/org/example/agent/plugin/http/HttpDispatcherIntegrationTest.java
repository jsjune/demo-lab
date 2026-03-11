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

import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        }
    }

    @Test
    void testDispatcherServletInstrumentation() throws Exception {
        Class<?> transformed = instrument(
            DummyDispatcherServlet.class,
            HttpPlugin.DispatcherServletAdvice.class,
            named("doDispatch").and(takesArguments(2)));

        Object instance = transformed.getDeclaredConstructor().newInstance();
        Method doDispatch = transformed.getDeclaredMethod("doDispatch",
            HttpServletRequest.class, HttpServletResponse.class);
        assertNotNull(doDispatch);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class);
             MockedStatic<HttpPlugin> pluginMock = mockStatic(HttpPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

            runtimeMock.when(() -> TraceRuntime.isSecondaryDispatch(any(Object.class))).thenReturn(false);
            pluginMock.when(() -> HttpPlugin.getRequestMethod(any(Object.class))).thenReturn("GET");
            pluginMock.when(() -> HttpPlugin.getRequestURI(any(Object.class))).thenReturn("/test");
            pluginMock.when(() -> HttpPlugin.getRequestHeader(any(Object.class), anyString())).thenReturn(null);
            pluginMock.when(() -> HttpPlugin.isAsyncStarted(any(Object.class))).thenReturn(false);
            pluginMock.when(() -> HttpPlugin.getResponseStatus(any(Object.class))).thenReturn(200);

            doDispatch.invoke(instance, request, response);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(eq(request), eq("GET"), eq("/test"), isNull(), isNull(), eq(false), anyLong()),
                times(1)
            );
            runtimeMock.verify(
                () -> TraceRuntime.onHttpInEnd(eq("GET"), eq("/test"), eq(200), anyLong()),
                times(1)
            );
        }
    }

    @Test
    void testErrorDispatchFiltering() throws Exception {
        Class<?> transformed = instrument(
            DummyDispatcherServlet.class,
            HttpPlugin.DispatcherServletAdvice.class,
            named("doDispatch").and(takesArguments(2)));

        Object instance = transformed.getDeclaredConstructor().newInstance();
        Method doDispatch = transformed.getDeclaredMethod("doDispatch",
            HttpServletRequest.class, HttpServletResponse.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            runtimeMock.when(() -> TraceRuntime.isSecondaryDispatch(any(Object.class))).thenReturn(true);
            runtimeMock.when(() -> TraceRuntime.restoreContext(any(Object.class))).thenAnswer(inv -> null);

            doDispatch.invoke(instance, request, response);

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(Object.class), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyLong()),
                never()
            );
        }
    }
}
