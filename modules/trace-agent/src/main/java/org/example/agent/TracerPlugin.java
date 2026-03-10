package org.example.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.example.agent.config.AgentConfig;

/**
 * Plugin SPI for ByteBuddy-based instrumentation.
 *
 * <p>Implementations register their instrumentation rules via {@link #install(AgentBuilder)}.
 * The AgentBuilder chain is built by PluginRegistry and installed once on the JVM Instrumentation.
 *
 * <p>The META-INF/services/org.example.agent.TracerPlugin file must list all implementations.
 */
public interface TracerPlugin {

    String pluginId();

    /**
     * Register instrumentation rules with the provided AgentBuilder.
     * Returns the (potentially modified) builder for chaining.
     */
    AgentBuilder install(AgentBuilder builder);

    default void init(AgentConfig config) {}

    default boolean isEnabled(AgentConfig config) {
        return Boolean.parseBoolean(AgentConfig.get("plugin." + pluginId() + ".enabled", "true"));
    }

    /** Plugin load order — lower values load first. Default: 100. */
    default int order() { return 100; }

    /** Return true if the agent jar must be appended to the Bootstrap ClassLoader search path. */
    default boolean requiresBootstrapSearch() { return false; }
}
