package org.example.agent.core;

import org.example.agent.TracerPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: PluginRegistry (플러그인 관리자)")
class PluginRegistryTest {

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
}
