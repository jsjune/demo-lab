package org.example.agent.core;

import org.example.agent.config.AgentConfig;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

public class CompositeTransformer implements ClassFileTransformer {
    private final List<ClassFileTransformer> transformers = new ArrayList<>();
    private final List<String> targetPrefixes = new ArrayList<>();

    public CompositeTransformer() {}

    public void addTransformer(ClassFileTransformer transformer) {
        transformers.add(transformer);
    }

    public void addTargetPrefixes(List<String> prefixes) {
        for (String prefix : prefixes) {
            if (prefix.length() <= 1) {
                AgentLogger.warn("[INSTRUMENT] Near-global scan prefix registered: '"
                    + prefix + "' — all loaded classes will be scanned.");
            }
        }
        targetPrefixes.addAll(prefixes);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (className == null) return null;

        // Always skip self
        if (className.startsWith("org/example/agent/")) return null;

        // Auto-detect Spring version from the first classloader that has Spring on its path.
        // Runs before any transformer so that the resolved profile is available to all plugins
        // (e.g., HttpPlugin needs servletPackage before instrumenting DispatcherServlet).
        if (loader != null && !AgentConfig.isSpringVersionResolved()) {
            AgentConfig.updateProfileFromLoader(loader);
            if (AgentConfig.isSpringVersionResolved()) {
                AgentLogger.info("Spring profile auto-detected: " + AgentConfig.getSpringVersionProfile());
            }
        }

        boolean matched = false;
        for (String prefix : targetPrefixes) {
            if (!prefix.isEmpty() && className.startsWith(prefix.replace('.', '/'))) {
                matched = true;
                break;
            }
        }
        if (!matched) return null;

        byte[] currentBuffer = classfileBuffer;
        for (ClassFileTransformer transformer : transformers) {
            try {
                byte[] transformed = transformer.transform(loader, className, classBeingRedefined, protectionDomain, currentBuffer);
                if (transformed != null) {
                    currentBuffer = transformed;
                }
            } catch (Throwable t) {
                AgentLogger.debug("[INSTRUMENT] Transform failed"
                    + " class=" + className
                    + " transformer=" + transformer.getClass().getSimpleName()
                    + " error=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return currentBuffer == classfileBuffer ? null : currentBuffer;
    }
}
