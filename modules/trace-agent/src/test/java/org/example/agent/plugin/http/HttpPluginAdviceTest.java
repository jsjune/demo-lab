package org.example.agent.plugin.http;

import org.example.agent.plugin.BaseAdvice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpPluginAdviceTest {

    @Nested
    @DisplayName("DispatcherServletAdvice 테스트")
    class DispatcherServletAdviceTest {

        @Test
        @DisplayName("onMethodEnter 시 isSecondaryDispatch 및 onHttpInStart가 호출되어야 한다")
        void onMethodEnter_callsRuntime() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            // descriptor index hints for isJakarta: contains "HttpServletRequest"
            HttpPlugin.DispatcherServletAdvice advice = 
                new HttpPlugin.DispatcherServletAdvice(mv, Opcodes.ACC_PROTECTED, "doDispatch", 
                    "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V", true);

            advice.onMethodEnter();

            // verify secondary dispatch check
            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("isSecondaryDispatch"),
                anyString(),
                eq(false)
            );

            // verify onHttpInStart call (new signature: 6 args)
            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onHttpInStart"),
                eq("(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V"),
                eq(false)
            );
        }
    }

    @Nested
    @DisplayName("DispatcherHandlerAdvice 테스트 (WebFlux)")
    class DispatcherHandlerAdviceTest {

        @Test
        @DisplayName("onMethodEnter 시 onWebFluxHandleStart 호출")
        void onMethodEnter_callsRuntime() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.DispatcherHandlerAdvice advice =
                new HttpPlugin.DispatcherHandlerAdvice(mv, Opcodes.ACC_PUBLIC, "handle",
                    "(Lorg/springframework/web/server/ServerWebExchange;)Lreactor/core/publisher/Mono;");

            advice.onMethodEnter();

            verify(mv, times(1)).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onWebFluxHandleStart"),
                anyString(),
                eq(false)
            );
        }

        @Test
        @DisplayName("onMethodExit(ARETURN) 시 wrapWebFluxHandle 및 clear 호출")
        void onMethodExit_callsRuntimeAndClear() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.DispatcherHandlerAdvice advice =
                new HttpPlugin.DispatcherHandlerAdvice(mv, Opcodes.ACC_PUBLIC, "handle",
                    "(Lorg/springframework/web/server/ServerWebExchange;)Lreactor/core/publisher/Mono;");

            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("wrapWebFluxHandle"),
                anyString(),
                eq(false)
            );
            
            // verify clear calls
            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TxIdHolder"),
                eq("clear"),
                anyString(),
                eq(false)
            );
        }
    }

    @Nested
    @DisplayName("RestTemplateAdvice 테스트")
    class RestTemplateAdviceTest {

        @Test
        @DisplayName("onMethodExit(ARETURN) 시 onHttpOut 호출")
        void onMethodExit_callsRuntime() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.RestTemplateAdvice advice =
                new RestTemplateAdviceFixed(mv, Opcodes.ACC_PROTECTED, "doExecute",
                    "(Ljava/net/URI;Lorg/springframework/http/HttpMethod;Lorg/springframework/web/client/RequestCallback;Lorg/springframework/web/client/ResponseExtractor;)Ljava/lang/Object;");

            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/core/TraceRuntime"),
                eq("onHttpOut"),
                anyString(),
                eq(false)
            );
        }

        // Helper to expose internal advice for testing
        class RestTemplateAdviceFixed extends HttpPlugin.RestTemplateAdvice {
            RestTemplateAdviceFixed(MethodVisitor mv, int access, String name, String desc) {
                super(mv, access, name, desc);
            }
            @Override public void onMethodExit(int opcode) { super.onMethodExit(opcode); }
        }
    }

    @Nested
    @DisplayName("RestTemplateCreateRequestAdvice 테스트")
    class RestTemplateCreateRequestAdviceTest {

        @Test
        @DisplayName("createRequest 종료 시 txId/spanId 헤더 주입(injectHeadersToRequest)이 호출되어야 한다")
        void onMethodExit_areturn_callsInjectHeadersToRequest() {
            MethodVisitor mv = Mockito.mock(MethodVisitor.class);
            HttpPlugin.RestTemplateCreateRequestAdvice advice =
                new HttpPlugin.RestTemplateCreateRequestAdvice(
                    mv, Opcodes.ACC_PROTECTED, "createRequest",
                    "(Ljava/net/URI;Lorg/springframework/http/HttpMethod;)Lorg/springframework/http/client/ClientHttpRequest;");

            advice.onMethodExit(Opcodes.ARETURN);

            verify(mv, atLeastOnce()).visitMethodInsn(
                eq(Opcodes.INVOKESTATIC),
                eq("org/example/agent/plugin/http/HttpPlugin"),
                eq("injectHeadersToRequest"),
                eq("(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V"),
                eq(false)
            );
        }
    }
}
