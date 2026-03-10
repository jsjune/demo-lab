package org.example.agent.plugin.http;

import org.example.agent.config.AgentConfig;
import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for HttpPlugin @Advice static methods (direct invocation).
 *
 * <p>ByteBuddy @Advice classes are plain static methods — we test them directly.
 */
class HttpPluginAdviceTest {

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
    }

    // -----------------------------------------------------------------------
    // DispatcherServletAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DispatcherServletAdvice 테스트")
    class DispatcherServletAdviceTest {

        @Test
        @DisplayName("primary dispatch: onHttpInStart가 호출되어야 한다")
        void enter_primaryDispatch_callsOnHttpInStart() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
                 MockedStatic<HttpPlugin> hp = mockStatic(HttpPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

                rt.when(() -> TraceRuntime.isSecondaryDispatch(any())).thenReturn(false);
                hp.when(() -> HttpPlugin.getRequestMethod(any())).thenReturn("GET");
                hp.when(() -> HttpPlugin.getRequestURI(any())).thenReturn("/api/test");
                hp.when(() -> HttpPlugin.getRequestHeader(any(), anyString())).thenReturn(null);

                Object fakeRequest = new Object();
                HttpPlugin.DispatcherServletAdvice.enter(fakeRequest, 0L, false);

                rt.verify(() -> TraceRuntime.onHttpInStart(eq(fakeRequest), eq("GET"), eq("/api/test"), isNull(), isNull(), eq(false)), times(1));
            }
        }

        @Test
        @DisplayName("secondary dispatch: isSecondaryDispatch true → onHttpInStart 미호출")
        void enter_secondaryDispatch_skipsOnHttpInStart() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                rt.when(() -> TraceRuntime.isSecondaryDispatch(any())).thenReturn(true);
                rt.when(() -> TraceRuntime.restoreContext(any())).thenAnswer(inv -> null);

                Object fakeRequest = new Object();
                HttpPlugin.DispatcherServletAdvice.enter(fakeRequest, 0L, false);

                rt.verify(() -> TraceRuntime.onHttpInStart(any(), any(), any(), any(), any(), anyBoolean()), never());
            }
        }

        @Test
        @DisplayName("exit: thrown != null → onHttpInError 호출")
        void exit_thrown_callsOnHttpInError() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
                 MockedStatic<HttpPlugin> hp = mockStatic(HttpPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

                hp.when(() -> HttpPlugin.getRequestMethod(any())).thenReturn("POST");
                hp.when(() -> HttpPlugin.getRequestURI(any())).thenReturn("/fail");

                Throwable err = new RuntimeException("boom");
                HttpPlugin.DispatcherServletAdvice.exit(new Object(), new Object(), err, 100L, true);

                rt.verify(() -> TraceRuntime.onHttpInError(eq(err), eq("POST"), eq("/fail"), anyLong()), times(1));
                rt.verify(() -> TraceRuntime.onHttpInEnd(any(), any(), anyInt(), anyLong()), never());
            }
        }

        @Test
        @DisplayName("exit: normal sync → onHttpInEnd 호출")
        void exit_normal_callsOnHttpInEnd() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class);
                 MockedStatic<HttpPlugin> hp = mockStatic(HttpPlugin.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {

                hp.when(() -> HttpPlugin.getRequestMethod(any())).thenReturn("GET");
                hp.when(() -> HttpPlugin.getRequestURI(any())).thenReturn("/ok");
                hp.when(() -> HttpPlugin.isAsyncStarted(any())).thenReturn(false);
                hp.when(() -> HttpPlugin.getResponseStatus(any())).thenReturn(200);

                HttpPlugin.DispatcherServletAdvice.exit(new Object(), new Object(), null, 100L, true);

                rt.verify(() -> TraceRuntime.onHttpInEnd(eq("GET"), eq("/ok"), eq(200), anyLong()), times(1));
            }
        }

        @Test
        @DisplayName("exit: isTracked=false → 아무것도 호출되지 않아야 한다")
        void exit_notTracked_skipsAll() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                HttpPlugin.DispatcherServletAdvice.exit(new Object(), new Object(), null, 100L, false);
                rt.verify(() -> TraceRuntime.onHttpInEnd(any(), any(), anyInt(), anyLong()), never());
                rt.verify(() -> TraceRuntime.onHttpInError(any(), any(), any(), anyLong()), never());
            }
        }
    }

    // -----------------------------------------------------------------------
    // RestTemplateAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("RestTemplate Advice 테스트")
    class RestTemplateAdviceTest {

        @Test
        @DisplayName("RestTemplateAdvice4Args: 정상 종료 시 onHttpOut 호출")
        void advice4Args_exit_normal_callsOnHttpOut() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                java.net.URI uri = java.net.URI.create("http://example.com/api");
                HttpPlugin.RestTemplateAdvice4Args.exit(uri, fakeHttpMethod("GET"), null, 100L);
                rt.verify(() -> TraceRuntime.onHttpOut(eq("GET"), eq("http://example.com/api"), eq(200), anyLong()), times(1));
            }
        }

        @Test
        @DisplayName("RestTemplateAdvice4Args: 예외 시 onHttpOutError 호출")
        void advice4Args_exit_thrown_callsOnHttpOutError() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                java.net.URI uri = java.net.URI.create("http://example.com/api");
                Throwable err = new RuntimeException("connection refused");
                HttpPlugin.RestTemplateAdvice4Args.exit(uri, fakeHttpMethod("POST"), err, 100L);
                rt.verify(() -> TraceRuntime.onHttpOutError(eq(err), eq("POST"), eq("http://example.com/api"), anyLong()), times(1));
            }
        }

        @Test
        @DisplayName("RestTemplateAdvice5Args: 정상 종료 시 onHttpOut 호출")
        void advice5Args_exit_normal_callsOnHttpOut() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                java.net.URI uri = java.net.URI.create("http://example.com/api");
                HttpPlugin.RestTemplateAdvice5Args.exit(uri, fakeHttpMethod("PUT"), null, 100L);
                rt.verify(() -> TraceRuntime.onHttpOut(eq("PUT"), eq("http://example.com/api"), eq(200), anyLong()), times(1));
            }
        }

        private enum FakeHttpMethod { GET, POST, PUT }

        private FakeHttpMethod fakeHttpMethod(String name) {
            return FakeHttpMethod.valueOf(name);
        }
    }

    // -----------------------------------------------------------------------
    // StartAsyncAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("StartAsyncAdvice 테스트")
    class StartAsyncAdviceTest {

        @Test
        @DisplayName("exit: registerAsyncListenerFromRequest가 호출되어야 한다")
        void exit_callsRegisterAsyncListenerFromRequest() {
            try (MockedStatic<TraceRuntime> rt = mockStatic(TraceRuntime.class)) {
                Object fakeRequest = new Object();
                HttpPlugin.StartAsyncAdvice.exit(fakeRequest);
                rt.verify(() -> TraceRuntime.registerAsyncListenerFromRequest(eq(fakeRequest)), times(1));
            }
        }
    }
}
