package org.example.agent.core;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.example.agent.TracerPlugin;
import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that PluginRegistry.install() chains all active plugin
 * AgentBuilder installations in order and that requiresAnyBootstrapSearch()
 * reports correctly.
 *
 * <p>Previously named CompositeTransformerTest — repurposed after
 * CompositeTransformer was removed in the ByteBuddy migration.
 */
class CompositeTransformerTest {

    private List<TracerPlugin> savedPlugins;

    @BeforeEach
    void setUp() throws Exception {
        savedPlugins = new ArrayList<>(activePlugins());
    }

    @AfterEach
    void tearDown() throws Exception {
        List<TracerPlugin> current = activePlugins();
        current.clear();
        current.addAll(savedPlugins);
    }

    @Test
    void install_chainsAllPluginsInOrder() throws Exception {
        List<String> callOrder = new ArrayList<>();

        TracerPlugin p1 = new NoopPlugin("p1") {
            @Override
            public AgentBuilder install(AgentBuilder builder) {
                callOrder.add("p1");
                return builder;
            }
        };
        TracerPlugin p2 = new NoopPlugin("p2") {
            @Override
            public AgentBuilder install(AgentBuilder builder) {
                callOrder.add("p2");
                return builder;
            }
        };

        List<TracerPlugin> plugins = activePlugins();
        plugins.clear();
        plugins.add(p1);
        plugins.add(p2);

        AgentBuilder builder = new AgentBuilder.Default();
        PluginRegistry.install(builder);

        assertEquals(List.of("p1", "p2"), callOrder);
    }

    @Test
    void requiresAnyBootstrapSearch_trueWhenOnePluginRequires() throws Exception {
        List<TracerPlugin> plugins = activePlugins();
        plugins.clear();
        plugins.add(new NoopPlugin("a"));
        plugins.add(new BootstrapPlugin("b"));

        assertTrue(PluginRegistry.requiresAnyBootstrapSearch());
    }

    @Test
    void requiresAnyBootstrapSearch_falseWhenNoneRequires() throws Exception {
        List<TracerPlugin> plugins = activePlugins();
        plugins.clear();
        plugins.add(new NoopPlugin("x"));
        plugins.add(new NoopPlugin("y"));

        assertFalse(PluginRegistry.requiresAnyBootstrapSearch());
    }

    @Test
    void activePluginIds_returnsIds() throws Exception {
        List<TracerPlugin> plugins = activePlugins();
        plugins.clear();
        plugins.add(new NoopPlugin("alpha"));
        plugins.add(new NoopPlugin("beta"));

        assertEquals(List.of("alpha", "beta"), PluginRegistry.activePluginIds());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<TracerPlugin> activePlugins() throws Exception {
        Field f = PluginRegistry.class.getDeclaredField("activePlugins");
        f.setAccessible(true);
        return (List<TracerPlugin>) f.get(null);
    }

    private static class NoopPlugin implements TracerPlugin {
        private final String id;
        NoopPlugin(String id) { this.id = id; }

        @Override public String pluginId() { return id; }
        @Override public AgentBuilder install(AgentBuilder builder) { return builder; }
        @Override public boolean requiresBootstrapSearch() { return false; }
    }

    private static class BootstrapPlugin extends NoopPlugin {
        BootstrapPlugin(String id) { super(id); }
        @Override public boolean requiresBootstrapSearch() { return true; }
    }
}
