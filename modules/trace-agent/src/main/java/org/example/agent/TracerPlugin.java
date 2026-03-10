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

    /** Initialize the plugin. Called once during agent load. */
    default void init() {}

    /** @deprecated Use {@link #init()} instead. */
    @Deprecated
    default void init(AgentConfig config) {
        init();
    }

    /** Returns true if this plugin should be activated based on configuration. */
    default boolean isEnabled() {
        return Boolean.parseBoolean(AgentConfig.get("plugin." + pluginId() + ".enabled", "true"));
    }

    /** @deprecated Use {@link #isEnabled()} instead. */
    @Deprecated
    default boolean isEnabled(AgentConfig config) {
        return isEnabled();
    }

    /** Plugin load order — lower values load first. Default: 100. */
    default int order() { return 100; }

    /** Return true if the agent jar must be appended to the Bootstrap ClassLoader search path. */
    default boolean requiresBootstrapSearch() { return false; }
}
