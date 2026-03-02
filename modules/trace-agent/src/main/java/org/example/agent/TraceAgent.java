package org.example.agent;

import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.CompositeTransformer;
import org.example.agent.core.PluginRegistry;
import org.example.agent.core.TcpSender;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

public class TraceAgent {
    public static void premain(String args, Instrumentation inst) {
        try {
            AgentConfig.init();
            AgentLogger.init();
            
            AgentLogger.info("Premain started");

            // FR-01: Config summary (assembled here to avoid AgentConfig → AgentLogger circular dependency)
            AgentLogger.info("Config:"
                + " server=" + AgentConfig.getServerName()
                + " collector=" + AgentConfig.getCollectorHost() + ":" + AgentConfig.getCollectorPort()
                + " sampling=" + AgentConfig.getSamplingRate() + "/" + AgentConfig.getSamplingStrategy()
                + " slowQueryMs=" + AgentConfig.getSlowQueryMs()
                + " logLevel=" + AgentConfig.getLogLevel());

            // FR-02: Spring profile initial state
            if (AgentConfig.isSpringVersionResolved()) {
                AgentLogger.info("Spring profile: " + AgentConfig.getSpringVersionProfile() + " (explicit config)");
            } else {
                AgentLogger.info("Spring profile: pending auto-detect");
            }

            PluginRegistry.load();

            // Bootstrap ClassLoader registration must happen BEFORE addTransformer()
            // so that instrumented bootstrap-loaded classes (e.g. java.io.*) can resolve TraceRuntime.
            if (PluginRegistry.requiresAnyBootstrapSearch()) {
                try {
                    File agentJar = new File(TraceAgent.class
                        .getProtectionDomain().getCodeSource().getLocation().toURI());
                    inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
                    AgentLogger.info("Registered agent jar to Bootstrap ClassLoader.");
                } catch (Exception e) {
                    AgentLogger.warn("Bootstrap registration failed: " + e.getMessage() + " — plugins requiring bootstrap may not work.");
                }
            }

            TcpSender.init();

            CompositeTransformer composite = new CompositeTransformer();
            for (ClassFileTransformer transformer : PluginRegistry.activeTransformers()) {
                composite.addTransformer(transformer);
            }
            composite.addTargetPrefixes(PluginRegistry.targetPrefixes());

            inst.addTransformer(composite, true);

            AgentLogger.info("Agent Initialized."
                + " Server: " + AgentConfig.getServerName()
                + ", Collector: " + AgentConfig.getCollectorHost() + ":" + AgentConfig.getCollectorPort()
                + ", Spring profile: " + AgentConfig.getSpringVersionProfile()
                + ", Active plugins: " + PluginRegistry.activePluginIds());
        } catch (Exception e) {
            AgentLogger.error("Failed to initialize agent", e);
        }
    }
}
