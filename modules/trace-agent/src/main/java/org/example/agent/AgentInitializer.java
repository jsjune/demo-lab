package org.example.agent;

import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.CompositeTransformer;
import org.example.agent.core.PluginRegistry;
import org.example.agent.core.TcpSender;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

/**
 * Core agent logic isolated from the JVM premain entry point.
 * This class is loaded via reflection AFTER the agent jar is added to the 
 * bootstrap classloader search path, ensuring that all ASM and core agent 
 * classes are resolved via the bootstrap loader, preventing IllegalAccessError.
 */
public class AgentInitializer {
    public static void initialize(String args, Instrumentation inst) {
        try {
            AgentConfig.init();
            AgentLogger.init();
            AgentLogger.info("AgentInitializer: Core logic starting");

            PluginRegistry.load();
            TcpSender.init();

            CompositeTransformer composite = new CompositeTransformer();
            for (ClassFileTransformer transformer : PluginRegistry.activeTransformers()) {
                composite.addTransformer(transformer);
            }
            composite.addTargetPrefixes(PluginRegistry.targetPrefixes());

            inst.addTransformer(composite, true);

            AgentLogger.info("Agent Initialized. Active plugins: " + PluginRegistry.activePluginIds());
        } catch (Throwable t) {
            System.err.println("[TraceAgent] Initialization failed in AgentInitializer: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
