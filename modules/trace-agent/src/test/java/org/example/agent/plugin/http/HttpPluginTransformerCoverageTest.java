package org.example.agent.plugin.http;

import org.example.agent.testutil.AsmTestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class HttpPluginTransformerCoverageTest {

    @Test
    void dispatcherHandlerTransformer_transformsMatchingClass() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "org/springframework/web/reactive/DispatcherHandler",
            AsmTestUtils.MethodSpec.of("handle",
                "(Lorg/springframework/web/server/ServerWebExchange;)Lreactor/core/publisher/Mono;"));

        HttpPlugin.DispatcherHandlerTransformer t = new HttpPlugin.DispatcherHandlerTransformer();
        byte[] out = t.transform(getClass().getClassLoader(),
            "org/springframework/web/reactive/DispatcherHandler", null, null, original);

        assertNotNull(out);
    }

    @Test
    void webClientTransformer_transformsMatchingClass() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "org/springframework/web/reactive/function/client/ExchangeFunctions$DefaultExchangeFunction",
            AsmTestUtils.MethodSpec.of("exchange",
                "(Lorg/springframework/web/reactive/function/client/ClientRequest;)Lreactor/core/publisher/Mono;"));

        HttpPlugin.WebClientTransformer t = new HttpPlugin.WebClientTransformer();
        byte[] out = t.transform(getClass().getClassLoader(),
            "org/springframework/web/reactive/function/client/ExchangeFunctions$DefaultExchangeFunction",
            null, null, original);

        assertNotNull(out);
    }

    @Test
    void restTemplateTransformer_transformsDoExecuteAndCreateRequest() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "org/springframework/web/client/RestTemplate",
            AsmTestUtils.MethodSpec.of("doExecute",
                "(Ljava/net/URI;Lorg/springframework/http/HttpMethod;Lorg/springframework/web/client/RequestCallback;Lorg/springframework/web/client/ResponseExtractor;)Ljava/lang/Object;"),
            AsmTestUtils.MethodSpec.of("createRequest",
                "(Ljava/net/URI;Lorg/springframework/http/HttpMethod;)Lorg/springframework/http/client/ClientHttpRequest;"));

        HttpPlugin.RestTemplateTransformer t = new HttpPlugin.RestTemplateTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "org/springframework/web/client/RestTemplate",
            null, null, original);

        assertNotNull(out);
    }

    @Test
    void httpServletRequestTransformer_transformsStartAsync() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "com/example/MyRequest",
            AsmTestUtils.MethodSpec.of("startAsync", "()Ljava/lang/Object;"));

        HttpPlugin.HttpServletRequestTransformer t = new HttpPlugin.HttpServletRequestTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "com/example/MyRequest", null, null, original);

        assertNotNull(out);
    }

    @Test
    void dispatcherHandlerTransformer_nonMatching_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods("com/example/Nope");
        HttpPlugin.DispatcherHandlerTransformer t = new HttpPlugin.DispatcherHandlerTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "com/example/Nope", null, null, original));
    }

    @Test
    void startAsyncAdvice_onMethodExit_callsRegister() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        HttpPlugin.StartAsyncAdvice advice = new HttpPlugin.StartAsyncAdvice(
            mv, Opcodes.ACC_PUBLIC, "startAsync", "()Ljava/lang/Object;");

        advice.onMethodExit(Opcodes.ARETURN);

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("registerAsyncListenerFromRequest"),
            eq("(Ljava/lang/Object;)V"),
            eq(false)
        );
    }
}
