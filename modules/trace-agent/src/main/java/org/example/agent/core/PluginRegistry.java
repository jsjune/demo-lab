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

    /** Aggregate of all additional ignore packages contributed by active plugins. */
    public static List<String> allAdditionalIgnorePackages() {
        List<String> result = new ArrayList<>();
        for (TracerPlugin p : activePlugins) {
            result.addAll(p.additionalIgnorePackages());
        }
        return result;
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
