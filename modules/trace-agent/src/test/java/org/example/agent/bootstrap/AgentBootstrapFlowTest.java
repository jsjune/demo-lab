package org.example.agent.bootstrap;

import org.example.agent.AgentInitializer;
import org.example.agent.TraceAgent;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.CompositeTransformer;
import org.example.agent.core.PluginRegistry;
import org.example.agent.core.TcpSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Bootstrap flow: TraceAgent/AgentInitializer wiring")
class AgentBootstrapFlowTest {

    @Test
    @DisplayName("AgentInitializer가 CompositeTransformer를 등록해야 한다")
    void initializer_registersCompositeTransformer() throws Exception {
        Instrumentation inst = mock(Instrumentation.class);
        ClassFileTransformer delegate = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                return new byte[]{7};
            }
        };

        try (MockedStatic<AgentConfig> config = mockStatic(AgentConfig.class);
             MockedStatic<AgentLogger> logger = mockStatic(AgentLogger.class);
             MockedStatic<PluginRegistry> registry = mockStatic(PluginRegistry.class);
             MockedStatic<TcpSender> sender = mockStatic(TcpSender.class)) {

            registry.when(PluginRegistry::activeTransformers).thenReturn(List.of(delegate));
            registry.when(PluginRegistry::targetPrefixes).thenReturn(List.of("a/b/"));
            registry.when(PluginRegistry::activePluginIds).thenReturn(List.of("http"));

            AgentInitializer.initialize(null, inst);

            var captor = org.mockito.ArgumentCaptor.forClass(ClassFileTransformer.class);
            verify(inst, times(1)).addTransformer(captor.capture(), eq(true));
            assertInstanceOf(CompositeTransformer.class, captor.getValue());

            byte[] out = captor.getValue().transform(getClass().getClassLoader(), "a/b/C", null, null, new byte[]{1});
            assertArrayEquals(new byte[]{7}, out);

            config.verify(AgentConfig::init, times(1));
            registry.verify(PluginRegistry::load, times(1));
            sender.verify(TcpSender::init, times(1));
        }
    }

    @Test
    @DisplayName("TraceAgent.premain은 bootstrap 등록 실패 시에도 initialize 위임을 시도해야 한다")
    void premain_delegatesEvenWhenBootstrapRegistrationFails() {
        Instrumentation inst = mock(Instrumentation.class);
        doThrow(new RuntimeException("boom"))
            .when(inst).appendToBootstrapClassLoaderSearch(any(JarFile.class));

        ClassFileTransformer delegate = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                return null;
            }
        };

        try (MockedStatic<AgentConfig> config = mockStatic(AgentConfig.class);
             MockedStatic<AgentLogger> logger = mockStatic(AgentLogger.class);
             MockedStatic<PluginRegistry> registry = mockStatic(PluginRegistry.class);
             MockedStatic<TcpSender> sender = mockStatic(TcpSender.class)) {

            registry.when(PluginRegistry::activeTransformers).thenReturn(List.of(delegate));
            registry.when(PluginRegistry::targetPrefixes).thenReturn(List.of("x/y/"));
            registry.when(PluginRegistry::activePluginIds).thenReturn(List.of("http"));

            assertDoesNotThrow(() -> TraceAgent.premain(null, inst));
            verify(inst, times(1)).addTransformer(any(ClassFileTransformer.class), eq(true));
        }
    }
}
