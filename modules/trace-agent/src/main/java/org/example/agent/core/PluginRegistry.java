package org.example.agent.core;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.example.agent.TracerPlugin;

import java.util.*;

public class PluginRegistry {
    private static final List<TracerPlugin> activePlugins = new ArrayList<>();

    public static synchronized void load() {
        if (!activePlugins.isEmpty()) return;
        ServiceLoader<TracerPlugin> loader = ServiceLoader.load(TracerPlugin.class);
        for (TracerPlugin plugin : loader) {
            if (plugin.isEnabled(null)) {
                try {
                    plugin.init(null);
                    activePlugins.add(plugin);
                    AgentLogger.info("Loaded plugin: " + plugin.pluginId() + " (order=" + plugin.order() + ")");
                } catch (Exception e) {
                    AgentLogger.warn("Failed to load plugin " + plugin.pluginId() + ": " + e.getMessage());
                }
            } else {
                AgentLogger.info("Plugin disabled: " + plugin.pluginId());
            }
        }
        activePlugins.sort(Comparator.comparingInt(TracerPlugin::order));
    }

    /**
     * Chain all active plugin installations into the AgentBuilder and return the result.
     * Called once during agent initialization.
     */
    public static AgentBuilder install(AgentBuilder builder) {
        for (TracerPlugin plugin : activePlugins) {
            builder = plugin.install(builder);
        }
        return builder;
    }

    /** True if any active plugin requires Bootstrap ClassLoader registration. */
    public static boolean requiresAnyBootstrapSearch() {
        return activePlugins.stream().anyMatch(TracerPlugin::requiresBootstrapSearch);
    }

    public static List<String> activePluginIds() {
        List<String> ids = new ArrayList<>();
        for (TracerPlugin plugin : activePlugins) {
            ids.add(plugin.pluginId());
        }
        return ids;
    }
}
