package org.example.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("설정: AgentConfig (에이전트 설정 로드)")
class AgentConfigTest {

    @Test
    @DisplayName("기본 설정값들이 올바르게 로드되어야 한다")
    void testGetDefaultValues() {
        assertEquals("trace-agent-node", AgentConfig.getServerName());
        assertEquals("localhost", AgentConfig.getCollectorHost());
        assertEquals(9200, AgentConfig.getCollectorPort());
        assertEquals("X-Tx-Id", AgentConfig.getHeaderKey());
    }

    @Test
    @DisplayName("샘플링 레이트 설정이 올바르게 반환되어야 한다")
    void testSamplingRate() {
        assertEquals(1.0, AgentConfig.getSamplingRate());
    }

    @Test
    @DisplayName("플러그인 활성화 상태가 설정 파일과 일치해야 한다")
    void testPluginStatus() {
        assertTrue(AgentConfig.isPluginEnabled("http"));
        assertTrue(AgentConfig.isPluginEnabled("jdbc"));
        assertFalse(AgentConfig.isPluginEnabled("file-io"));
    }

    @Test
    @DisplayName("플러그인 target-prefixes override/add 설정이 반영되어야 한다")
    void testPluginTargetPrefixesConfig() {
        System.setProperty("trace.agent.plugin.test-ext.target-prefixes", "a.b.C, x/y/");
        System.setProperty("trace.agent.plugin.test-ext.target-prefixes.add", "x.y/,z.k");
        AgentConfig.init();

        List<String> prefixes = AgentConfig.getPluginTargetPrefixes(
            "test-ext",
            Arrays.asList("default/one", "default/two"));

        assertEquals(Arrays.asList("a/b/C", "x/y/", "z/k"), prefixes);
    }

    // -----------------------------------------------------------------------
    // sender.mode / batch 설정 기본값 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("sender.mode 기본값은 'single'이어야 한다")
    void getSenderMode_defaultIsSingle() {
        assertEquals("single", AgentConfig.getSenderMode());
    }

    @Test
    @DisplayName("sender.batch.size 기본값은 50이어야 한다")
    void getBatchSize_defaultIs50() {
        assertEquals(50, AgentConfig.getBatchSize());
    }

    @Test
    @DisplayName("sender.batch.flush-ms 기본값은 500이어야 한다")
    void getBatchFlushMs_defaultIs500() {
        assertEquals(500L, AgentConfig.getBatchFlushMs());
    }

    // -----------------------------------------------------------------------
    // sender.mode / batch 설정 커스텀값 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("sender.mode=batch 설정 시 'batch'를 반환해야 한다")
    void getSenderMode_customBatch_returnsBatch() throws Exception {
        setProperty("sender.mode", "batch");
        try {
            assertEquals("batch", AgentConfig.getSenderMode());
        } finally {
            removeProperty("sender.mode");
        }
    }

    @Test
    @DisplayName("sender.batch.size=100 설정 시 100을 반환해야 한다")
    void getBatchSize_customValue_returnsConfiguredValue() throws Exception {
        setProperty("sender.batch.size", "100");
        try {
            assertEquals(100, AgentConfig.getBatchSize());
        } finally {
            removeProperty("sender.batch.size");
        }
    }

    @Test
    @DisplayName("sender.batch.flush-ms=1000 설정 시 1000을 반환해야 한다")
    void getBatchFlushMs_customValue_returnsConfiguredValue() throws Exception {
        setProperty("sender.batch.flush-ms", "1000");
        try {
            assertEquals(1000L, AgentConfig.getBatchFlushMs());
        } finally {
            removeProperty("sender.batch.flush-ms");
        }
    }

    // -----------------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------------

    private void setProperty(String key, String value) throws Exception {
        Field propsField = AgentConfig.class.getDeclaredField("props");
        propsField.setAccessible(true);
        ((Properties) propsField.get(null)).setProperty(key, value);
    }

    private void removeProperty(String key) throws Exception {
        Field propsField = AgentConfig.class.getDeclaredField("props");
        propsField.setAccessible(true);
        ((Properties) propsField.get(null)).remove(key);
    }
}
