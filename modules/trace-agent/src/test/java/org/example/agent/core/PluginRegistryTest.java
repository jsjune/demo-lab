package org.example.agent.core;

import org.example.agent.TracerPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
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
        // Ensure plugins are loaded
        PluginRegistry.load();
        List<String> pluginIds = PluginRegistry.activePluginIds();
        
        assertNotNull(pluginIds);
        assertFalse(pluginIds.isEmpty(), "기본적으로 활성화된 플러그인이 존재해야 함");
        
        // 특정 플러그인(예: http)이 포함되어 있는지 확인
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
        plugins.add(new FakePlugin("a", false, List.of(), List.of()));
        plugins.add(new FakePlugin("b", true, List.of(), List.of()));

        assertTrue(PluginRegistry.requiresAnyBootstrapSearch());
    }

    @Test
    @DisplayName("활성 플러그인들의 transformer와 target prefix를 순서대로 합쳐야 한다")
    void activeTransformersAndTargetPrefixes_areConcatenatedInOrder() throws Exception {
        ClassFileTransformer t1 = new ClassFileTransformer() {};
        ClassFileTransformer t2 = new ClassFileTransformer() {};

        List<TracerPlugin> plugins = activePlugins();
        plugins.clear();
        plugins.add(new FakePlugin("p1", false, List.of("x/a"), List.of(t1)));
        plugins.add(new FakePlugin("p2", false, List.of("x/b"), List.of(t2)));

        List<String> prefixes = PluginRegistry.targetPrefixes();
        List<ClassFileTransformer> transformers = PluginRegistry.activeTransformers();

        assertEquals(List.of("x/a", "x/b"), prefixes);
        assertEquals(List.of(t1, t2), transformers);
    }

    @SuppressWarnings("unchecked")
    private List<TracerPlugin> activePlugins() throws Exception {
        Field f = PluginRegistry.class.getDeclaredField("activePlugins");
        f.setAccessible(true);
        return (List<TracerPlugin>) f.get(null);
    }

    private static final class FakePlugin implements TracerPlugin {
        private final String id;
        private final boolean bootstrap;
        private final List<String> prefixes;
        private final List<ClassFileTransformer> transformers;

        private FakePlugin(String id, boolean bootstrap, List<String> prefixes, List<ClassFileTransformer> transformers) {
            this.id = id;
            this.bootstrap = bootstrap;
            this.prefixes = prefixes;
            this.transformers = transformers;
        }

        @Override
        public String pluginId() {
            return id;
        }

        @Override
        public List<String> targetClassPrefixes() {
            return prefixes;
        }

        @Override
        public List<ClassFileTransformer> transformers() {
            return transformers;
        }

        @Override
        public boolean requiresBootstrapSearch() {
            return bootstrap;
        }
    }
}
