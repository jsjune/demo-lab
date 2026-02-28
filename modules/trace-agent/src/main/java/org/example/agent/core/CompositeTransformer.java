package org.example.agent.core;

import org.example.agent.config.AgentConfig;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CompositeTransformer implements ClassFileTransformer {
    private final List<ClassFileTransformer> transformers = new ArrayList<>();
    private final List<String> targetPrefixes = new ArrayList<>();

    // Base packages that should NEVER be instrumented — not overridable externally
    private static final String[] BASE_IGNORE_PACKAGES = {
        "java/", "javax/", "jakarta/", "sun/", "com/sun/", "jdk/",
        "org/springframework/", "org/apache/", "io/netty/", "io/micrometer/",
        "io/lettuce/", "reactor/", "com/fasterxml/", "org/objectweb/asm/",
        "org/projectlombok/", "net/bytebuddy/", "org/aspectj/", "com/google/",
        "org/hibernate/", "org/jboss/", "org/antlr/", "com/zaxxer/hikari/", "ch/qos/logback/", "org/slf4j/",
        "org/h2/", "com/mysql/", "org/postgresql/", "com/microsoft/sqlserver/", "oracle/"
    };

    // Instance-level final ignore list (base + plugin-contributed extras)
    private final String[] ignorePackages;

    public CompositeTransformer() {
        this.ignorePackages = BASE_IGNORE_PACKAGES;
    }

    public CompositeTransformer(List<String> additionalIgnores) {
        if (additionalIgnores == null || additionalIgnores.isEmpty()) {
            this.ignorePackages = BASE_IGNORE_PACKAGES;
        } else {
            List<String> combined = new ArrayList<>(Arrays.asList(BASE_IGNORE_PACKAGES));
            combined.addAll(additionalIgnores);
            this.ignorePackages = combined.toArray(new String[0]);
        }
    }

    public void addTransformer(ClassFileTransformer transformer) {
        transformers.add(transformer);
    }

    public void addTargetPrefixes(List<String> prefixes) {
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

        boolean isExplicitMatch = false;
        boolean allowsGlobalScan = false;

        for (String prefix : targetPrefixes) {
            if (prefix.isEmpty()) {
                allowsGlobalScan = true;
            } else if (className.startsWith(prefix.replace('.', '/'))) {
                isExplicitMatch = true;
                break;
            }
        }

        if (!isExplicitMatch && !allowsGlobalScan) return null;

        // Global scan candidates must pass the ignore list; explicit matches bypass it
        if (!isExplicitMatch) {
            for (String pkg : ignorePackages) {
                if (className.startsWith(pkg)) return null;
            }
        }

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
