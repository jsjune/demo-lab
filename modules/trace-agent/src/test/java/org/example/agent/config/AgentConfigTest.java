package org.example.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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
}
