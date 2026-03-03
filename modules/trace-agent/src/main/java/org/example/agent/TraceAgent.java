package org.example.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * JVM agent entry point.
 * Performs critical bootstrap registration immediately, then delegates
 * core logic to AgentInitializer via reflection to avoid ClassLoader split.
 */
public class TraceAgent {
    public static void premain(String args, Instrumentation inst) {
        try {
            // 1. Register agent jar to Bootstrap ClassLoader search path.
            // This MUST happen first so that shaded ASM classes are found by the bootstrap loader
            // when instrumenting JDK core classes like ThreadPoolExecutor.
            try {
                File agentJar = new File(TraceAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
            } catch (Exception e) {
                System.err.println("[TraceAgent] Error registering to Bootstrap: " + e.getMessage());
            }

            // 2. Delegate to AgentInitializer via reflection.
            // Loading AgentInitializer AFTER the bootstrap path update ensures all its
            // transitive dependencies (ASM, PluginRegistry, etc.) are resolved consistently.
            Class.forName("org.example.agent.AgentInitializer")
                 .getMethod("initialize", String.class, Instrumentation.class)
                 .invoke(null, args, inst);

        } catch (Throwable t) {
            System.err.println("[TraceAgent] Fatal error in premain: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
