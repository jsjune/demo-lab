package org.example.agent;

import org.example.agent.config.AgentConfig;

import java.lang.instrument.ClassFileTransformer;
import java.util.Collections;
import java.util.List;

public interface TracerPlugin {
    String pluginId();
    List<String> targetClassPrefixes();
    List<ClassFileTransformer> transformers();

    default void init(AgentConfig config) {}
    default boolean isEnabled(AgentConfig config) {
        return Boolean.parseBoolean(AgentConfig.get("plugin." + pluginId() + ".enabled", "true"));
    }

    /** Plugin load order — lower values load first. Default: 100. */
    default int order() { return 100; }

    /** Return true if the agent jar must be appended to the Bootstrap ClassLoader. */
    default boolean requiresBootstrapSearch() { return false; }

    /** Additional packages to exclude from the global instrumentation scan. */
    default List<String> additionalIgnorePackages() { return Collections.emptyList(); }
}
