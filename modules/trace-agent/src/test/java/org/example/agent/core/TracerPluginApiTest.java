package org.example.agent.core;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.example.agent.TracerPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Agent Plugin API Tests")
class TracerPluginApiTest {

    @Test
    @DisplayName("TracerPlugin should have no-arg isEnabled and init methods")
    void tracerPlugin_hasNoArgMethods() {
        TracerPlugin plugin = new TracerPlugin() {
            public boolean initCalled = false;
            @Override public String pluginId() { return "test"; }
            @Override public AgentBuilder install(AgentBuilder b) { return b; }
            @Override public void init() { this.initCalled = true; }
        };

        assertTrue(plugin.isEnabled(), "Default isEnabled() should be true");
        plugin.init();
        // This test only compiles if no-arg methods exist
    }
}
