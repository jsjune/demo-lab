package org.example.agent.core;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.example.agent.TracerPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: PluginRegistry (플러그인 관리자)")
class PluginRegistryTest {
    private List<TracerPlugin> originalPlugins;

    @BeforeEach
    void setUp() throws Exception {
        originalPlugins = new ArrayList<>(activePlugins());
    }

    @AfterEach
    void tearDown() throws Exception {
        List<TracerPlugin> current = activePlugins();
        current.clear();
        current.addAll(originalPlugins);
    }

    @Test
    @DisplayName("플러그인 로드 시 활성화된 모든 플러그인 ID를 반환해야 한다")
    void testActivePluginIds() {
        PluginRegistry.load();
        List<String> pluginIds = PluginRegistry.activePluginIds();

        assertNotNull(pluginIds);
        assertFalse(pluginIds.isEmpty(), "기본적으로 활성화된 플러그인이 존재해야 함");
        assertTrue(pluginIds.contains("http"), "http 플러그인이 포함되어야 함");
    }

    @Test
    @DisplayName("활성화된 플러그인 ID는 모두 고유해야 한다")
    void testUniquePluginIds() {
        PluginRegistry.load();
        List<String> pluginIds = PluginRegistry.activePluginIds();
        long uniqueCount = pluginIds.stream().distinct().count();

        assertEquals(pluginIds.size(), uniqueCount, "모든 플러그인 ID는 고유해야 함");
    }

    @Test
    @DisplayName("활성 플러그인 중 하나라도 bootstrap 검색이 필요하면 true를 반환해야 한다")
    void requiresAnyBootstrapSearch_trueWhenAnyPluginRequires() throws Exception {
        List<TracerPlugin> plugins = activePlugins();
        plugins.clear();
        plugins.add(new FakePlugin("a", false));
        plugins.add(new FakePlugin("b", true));

        assertTrue(PluginRegistry.requiresAnyBootstrapSearch());
    }

    @Test
    @DisplayName("install()은 모든 플러그인의 install을 순서대로 호출해야 한다")
    void install_callsEachPluginInstall() throws Exception {
        List<String> order = new ArrayList<>();

        List<TracerPlugin> plugins = activePlugins();
        plugins.clear();
        plugins.add(new FakePlugin("p1", false) {
            @Override public AgentBuilder install(AgentBuilder b) { order.add("p1"); return b; }
        });
        plugins.add(new FakePlugin("p2", false) {
            @Override public AgentBuilder install(AgentBuilder b) { order.add("p2"); return b; }
        });

        PluginRegistry.install(new AgentBuilder.Default());

        assertEquals(List.of("p1", "p2"), order);
    }

    @SuppressWarnings("unchecked")
    private List<TracerPlugin> activePlugins() throws Exception {
        Field f = PluginRegistry.class.getDeclaredField("activePlugins");
        f.setAccessible(true);
        return (List<TracerPlugin>) f.get(null);
    }

    private static class FakePlugin implements TracerPlugin {
        private final String id;
        private final boolean bootstrap;

        FakePlugin(String id, boolean bootstrap) {
            this.id = id;
            this.bootstrap = bootstrap;
        }

        @Override public String pluginId() { return id; }
        @Override public AgentBuilder install(AgentBuilder builder) { return builder; }
        @Override public boolean requiresBootstrapSearch() { return bootstrap; }
    }
}
