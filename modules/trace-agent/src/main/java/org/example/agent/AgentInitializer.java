package org.example.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.PluginRegistry;
import org.example.agent.core.TcpSender;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

/**
 * Core agent logic isolated from the JVM premain entry point.
 */
public class AgentInitializer {
    private static ClassFileLocator agentLocator;

    public static ClassFileLocator getAgentLocator() {
        return agentLocator;
    }

    public static void initialize(String args, Instrumentation inst) {
        try {
            AgentConfig.init();
            AgentLogger.init();
            AgentLogger.info("AgentInitializer: Core logic starting");

            // Initialize agentLocator using multiple sources to ensure agent classes (Advice) are found
            try {
                java.security.ProtectionDomain pd = AgentInitializer.class.getProtectionDomain();
                java.security.CodeSource cs = (pd != null) ? pd.getCodeSource() : null;
                java.net.URL agentJarUrl = (cs != null) ? cs.getLocation() : null;

                java.io.File agentJar = null;
                if (agentJarUrl != null) {
                    agentJar = new java.io.File(agentJarUrl.toURI());
                } else {
                    // Fallback: try to find trace-agent jar in classpath
                    String classpath = System.getProperty("java.class.path");
                    for (String path : classpath.split(java.io.File.pathSeparator)) {
                        if (path.contains("trace-agent") && path.endsWith(".jar")) {
                            agentJar = new java.io.File(path);
                            break;
                        }
                    }
                }

                if (agentJar != null && agentJar.exists()) {
                    AgentLogger.info("Agent JAR located at: " + agentJar.getAbsolutePath());
                    agentLocator = new ClassFileLocator.Compound(
                        ClassFileLocator.ForClassLoader.of(AgentInitializer.class.getClassLoader()),
                        ClassFileLocator.ForClassLoader.of(ClassLoader.getSystemClassLoader()),
                        ClassFileLocator.ForJarFile.of(agentJar)
                    );
                } else {
                    AgentLogger.warn("Could not locate Agent JAR file. ByteBuddy inlining might be limited.");
                    agentLocator = new ClassFileLocator.Compound(
                        ClassFileLocator.ForClassLoader.of(AgentInitializer.class.getClassLoader()),
                        ClassFileLocator.ForClassLoader.of(ClassLoader.getSystemClassLoader())
                    );
                }
            } catch (Exception e) {
                AgentLogger.error("Failed to initialize agentLocator: " + e.getMessage(), e);
                agentLocator = new ClassFileLocator.Compound(
                    ClassFileLocator.ForClassLoader.of(AgentInitializer.class.getClassLoader()),
                    ClassFileLocator.ForClassLoader.of(ClassLoader.getSystemClassLoader())
                );
            }


            PluginRegistry.load();
            TcpSender.init();

            // Use default AgentBuilder but log errors for visibility
            AgentBuilder builder = new AgentBuilder.Default()
                .ignore(nameStartsWith("org.example.agent."))
                .with(new SpringVersionDetectorListener())
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());

            builder = PluginRegistry.install(builder);
            builder.installOn(inst);

            AgentLogger.info("Agent Initialized. Active plugins: " + PluginRegistry.activePluginIds());
        } catch (Throwable t) {
            System.err.println("[TraceAgent] Initialization failed in AgentInitializer: " + t.getMessage());
            t.printStackTrace();
        }
    }

    static class SpringVersionDetectorListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(TypeDescription typeDescription,
                                     ClassLoader classLoader,
                                     JavaModule module,
                                     boolean loaded,
                                     DynamicType dynamicType) {
            if (classLoader != null && !AgentConfig.isSpringVersionResolved()) {
                AgentConfig.updateProfileFromLoader(classLoader);
                if (AgentConfig.isSpringVersionResolved()) {
                    AgentLogger.info("Spring profile auto-detected: " + AgentConfig.getSpringVersionProfile());
                }
            }
        }
    }
}
