package org.example.agent.plugin.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("플러그인: HTTP (Advice 바이트코드 검증)")
class HttpPluginAdviceTest {

    private static final String JAKARTA_DESCRIPTOR =
        "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V";

    // -----------------------------------------------------------------------
    // DispatcherServletAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DispatcherServletAdvice")
    class DispatcherServletAdviceTest {

        @Test
        @DisplayName("doDispatch 진입 시 isErrorDispatch를 호출해야 한다 (에러 디스패치 감지)")
        void onMethodEnter_callsIsErrorDispatch() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.DispatcherServletAdvice advice = new HttpPlugin.DispatcherServletAdvice(
                mv, Opcodes.ACC_PROTECTED, "doDispatch", JAKARTA_DESCRIPTOR, true);

            advice.onMethodEnter();

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("isErrorDispatch"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("doDispatch 진입 시 onHttpInStart를 조건부로 호출해야 한다")
        void onMethodEnter_callsOnHttpInStart() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.DispatcherServletAdvice advice = new HttpPlugin.DispatcherServletAdvice(
                mv, Opcodes.ACC_PROTECTED, "doDispatch", JAKARTA_DESCRIPTOR, true);

            advice.onMethodEnter();

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onHttpInStart"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("doDispatch 정상 종료(RETURN) 시 onHttpInEnd를 조건부로 호출해야 한다")
        void onMethodExit_normalReturn_callsOnHttpInEnd() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.DispatcherServletAdvice advice = new HttpPlugin.DispatcherServletAdvice(
                mv, Opcodes.ACC_PROTECTED, "doDispatch", JAKARTA_DESCRIPTOR, true);

            advice.onMethodEnter(); // isTrackedId 초기화 필수
            advice.onMethodExit(Opcodes.RETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onHttpInEnd"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("doDispatch 예외 종료(ATHROW) 시 onHttpInError를 조건부로 호출해야 한다")
        void onMethodExit_athrow_callsOnHttpInError() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.DispatcherServletAdvice advice = new HttpPlugin.DispatcherServletAdvice(
                mv, Opcodes.ACC_PROTECTED, "doDispatch", JAKARTA_DESCRIPTOR, true);

            advice.onMethodEnter(); // isTrackedId 초기화 필수
            advice.onMethodExit(Opcodes.ATHROW);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onHttpInError"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("@TraceIgnore가 붙은 메서드는 isErrorDispatch와 onHttpInStart를 호출하지 않아야 한다")
        void traceIgnore_skipsAllInjection() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.DispatcherServletAdvice advice = new HttpPlugin.DispatcherServletAdvice(
                mv, Opcodes.ACC_PROTECTED, "doDispatch", JAKARTA_DESCRIPTOR, true);

            // @TraceIgnore 어노테이션 방문 → ignored = true 설정
            advice.visitAnnotation("Lorg/example/agent/core/TraceIgnore;", true);
            advice.onMethodEnter();
            advice.onMethodExit(Opcodes.RETURN);

            verify(mv, never()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("isErrorDispatch"),
                anyString(),
                eq(false)
            );
            verify(mv, never()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onHttpInStart"),
                anyString(),
                eq(false)
            );
        }
    }

    // -----------------------------------------------------------------------
    // RestTemplateCreateRequestAdvice
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("RestTemplateCreateRequestAdvice")
    class RestTemplateCreateRequestAdviceTest {

        @Test
        @DisplayName("createRequest 종료 시 txId 헤더 주입(injectHeaderToRequest)이 호출되어야 한다")
        void onMethodExit_areturn_callsInjectHeaderToRequest() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.RestTemplateCreateRequestAdvice advice =
                new HttpPlugin.RestTemplateCreateRequestAdvice(
                    mv, Opcodes.ACC_PROTECTED, "createRequest",
                    "(Ljava/net/URI;Lorg/springframework/http/HttpMethod;)Lorg/springframework/http/client/ClientHttpRequest;");

            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/plugin/http/HttpPlugin"),
                eq("injectHeaderToRequest"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("createRequest가 RETURN(non-ARETURN)으로 종료되면 헤더를 주입하지 않아야 한다")
        void onMethodExit_nonAreturn_doesNotInjectHeader() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.RestTemplateCreateRequestAdvice advice =
                new HttpPlugin.RestTemplateCreateRequestAdvice(
                    mv, Opcodes.ACC_PROTECTED, "createRequest",
                    "(Ljava/net/URI;Lorg/springframework/http/HttpMethod;)Lorg/springframework/http/client/ClientHttpRequest;");

            advice.onMethodExit(Opcodes.RETURN);

            verify(mv, never()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/plugin/http/HttpPlugin"),
                eq("injectHeaderToRequest"),
                anyString(),
                eq(false)
            );
        }
    }
}
