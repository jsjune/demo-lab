package org.example.agent.core;

import org.example.agent.TracerPlugin;

import java.lang.instrument.ClassFileTransformer;
import java.util.*;

public class PluginRegistry {
    private static final List<TracerPlugin> activePlugins = new ArrayList<>();

    public static synchronized void load() {
        if (!activePlugins.isEmpty()) return;
        ServiceLoader<TracerPlugin> loader = ServiceLoader.load(TracerPlugin.class);
        for (TracerPlugin plugin : loader) {
            // InstrumentationContext is null: the current SPI contract does not require
            // a context object — all configuration is read via AgentConfig directly.
            // SPI implementations must tolerate null (documented in TracerPlugin interface).
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

    /** True if any active plugin requires Bootstrap ClassLoader registration. */
    public static boolean requiresAnyBootstrapSearch() {
        return activePlugins.stream().anyMatch(TracerPlugin::requiresBootstrapSearch);
    }

    public static List<ClassFileTransformer> activeTransformers() {
        List<ClassFileTransformer> transformers = new ArrayList<>();
        for (TracerPlugin plugin : activePlugins) {
            transformers.addAll(plugin.transformers());
        }
        return transformers;
    }

    public static List<String> targetPrefixes() {
        List<String> prefixes = new ArrayList<>();
        for (TracerPlugin plugin : activePlugins) {
            prefixes.addAll(plugin.targetClassPrefixes());
        }
        return prefixes;
    }

    public static List<String> activePluginIds() {
        List<String> ids = new ArrayList<>();
        for (TracerPlugin plugin : activePlugins) {
            ids.add(plugin.pluginId());
        }
        return ids;
    }
}
