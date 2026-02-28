package org.example.agent.integration;

import org.example.agent.core.TraceIgnore;
import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.http.HttpPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("통합: HTTP @TraceIgnore 지원 검증")
class HttpAgentIntegrationTest extends ByteBuddyIntegrationTest {

    @Test
    @DisplayName("@TraceIgnore 어노테이션이 붙은 서블릿 메서드는 인스트루멘테이션을 건너뛰어야 한다")
    void testTraceIgnoreOnDispatcherServlet() throws Exception {
        HttpPlugin plugin = new HttpPlugin();
        // The transformer for DispatcherServlet
        var transformer = plugin.transformers().get(0);

        Class<?> transformedClass = transformAndLoad(IgnoredController.class, transformer);
        Object instance = transformedClass.getDeclaredConstructor().newInstance();
        
        // Mocking DispatcherServlet.doDispatch arity
        Method method = transformedClass.getDeclaredMethod("doDispatch", Object.class, Object.class);
        method.setAccessible(true);

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            method.invoke(instance, new Object(), new Object());

            // Verify that onHttpInStart was NOT called
            runtimeMock.verify(() -> TraceRuntime.onHttpInStart(anyString(), anyString(), anyString()), never());
        }
    }

    @Test
    @DisplayName("RestTemplate.createRequest 호출 시 트랜잭션 ID가 헤더에 주입되어야 한다")
    void testRestTemplatePropagation() throws Exception {
        HttpPlugin plugin = new HttpPlugin();
        var transformer = plugin.transformers().get(1); // RestTemplateTransformer

        String currentTxId = "client-tx-777";
        org.example.agent.core.TxIdHolder.set(currentTxId);

        Class<?> transformedClass = transformAndLoad(DummyRestTemplate.class, transformer);
        Object instance = transformedClass.getDeclaredConstructor().newInstance();
        Method method = transformedClass.getDeclaredMethod("createRequest", java.net.URI.class, org.springframework.http.HttpMethod.class);
        method.setAccessible(true);

        Object request = method.invoke(instance, new java.net.URI("http://test"), org.springframework.http.HttpMethod.GET);
        
        // The mock request should have the header
        java.util.List<String> headers = ((org.springframework.http.client.ClientHttpRequest)request).getHeaders().get("X-Tx-Id");
        assertNotNull(headers);
        assertEquals(currentTxId, headers.get(0));
    }

    public static class DummyRestTemplate extends org.springframework.http.client.support.HttpAccessor {
        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI url, org.springframework.http.HttpMethod method) {
            return new org.springframework.mock.http.client.MockClientHttpRequest();
        }
    }

    public static class IgnoredController {
        @TraceIgnore
        protected void doDispatch(Object request, Object response) {
            // Business logic
        }
    }
}
