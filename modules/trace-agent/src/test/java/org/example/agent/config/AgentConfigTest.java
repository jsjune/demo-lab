package org.example.agent.config;

import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("설정: AgentConfig (에이전트 설정 로드)")
class AgentConfigTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
    }

    @AfterEach
    void tearDown() {
        setResolvedProfileQuietly(null);
        stateGuard.close();
    }

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
        stateGuard.setSystemProperty("trace.agent.plugin.test-ext.target-prefixes", "a.b.C, x/y/");
        stateGuard.setSystemProperty("trace.agent.plugin.test-ext.target-prefixes.add", "x.y/,z.k");
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
        assertEquals("batch", AgentConfig.getSenderMode());
    }

    @Test
    @DisplayName("sender.batch.size=100 설정 시 100을 반환해야 한다")
    void getBatchSize_customValue_returnsConfiguredValue() throws Exception {
        setProperty("sender.batch.size", "100");
        assertEquals(100, AgentConfig.getBatchSize());
    }

    @Test
    @DisplayName("sender.batch.flush-ms=1000 설정 시 1000을 반환해야 한다")
    void getBatchFlushMs_customValue_returnsConfiguredValue() throws Exception {
        setProperty("sender.batch.flush-ms", "1000");
        assertEquals(1000L, AgentConfig.getBatchFlushMs());
    }

    @Test
    @DisplayName("spring.version 미설정 시 기본 프로필은 SPRING_5")
    void getSpringVersionProfile_defaultIsSpring5() {
        assertEquals(SpringVersionProfile.SPRING_5, AgentConfig.getSpringVersionProfile());
    }

    @Test
    @DisplayName("resolvedProfile 설정 시 servlet package가 프로필과 일치해야 한다")
    void getServletPackage_matchesResolvedProfile() throws Exception {
        setResolvedProfile(SpringVersionProfile.SPRING_6_1);
        assertEquals("jakarta/servlet/http", AgentConfig.getServletPackage());
    }

    @Test
    @DisplayName("http.* 클래스 오버라이드 값이 우선되어야 한다")
    void httpClassOverrides_arePreferred() throws Exception {
        setProperty("http.dispatcher.class", "a/b/CDispatcher");
        setProperty("http.resttemplate.class", "a/b/CRestTemplate");
        setProperty("http.webclient.class.prefix", "a/b/CWebClient");

        assertEquals("a/b/CDispatcher", AgentConfig.getHttpDispatcherClass());
        assertEquals("a/b/CRestTemplate", AgentConfig.getHttpRestTemplateClass());
        assertEquals("a/b/CWebClient", AgentConfig.getHttpWebClientClassPrefix());
    }

    @Test
    @DisplayName("updateProfileFromLoader로 profile이 해석되면 resolved 상태가 true여야 한다")
    void updateProfileFromLoader_marksResolved() throws Exception {
        setResolvedProfile(null);
        ClassLoader loader = selectiveLoader("jakarta.servlet.http.HttpServletRequest");

        AgentConfig.updateProfileFromLoader(loader);

        assertTrue(AgentConfig.isSpringVersionResolved());
        assertEquals(SpringVersionProfile.SPRING_6_0, AgentConfig.getSpringVersionProfile());
    }

    @Test
    @DisplayName("이미 resolvedProfile이 있으면 updateProfileFromLoader는 변경하지 않아야 한다")
    void updateProfileFromLoader_doesNotOverrideResolvedProfile() throws Exception {
        setResolvedProfile(SpringVersionProfile.SPRING_5);
        ClassLoader loader = selectiveLoader(
            "jakarta.servlet.http.HttpServletRequest",
            "org.springframework.web.client.RestClient");

        AgentConfig.updateProfileFromLoader(loader);

        assertEquals(SpringVersionProfile.SPRING_5, AgentConfig.getSpringVersionProfile());
    }

    // -----------------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------------

    private void setProperty(String key, String value) throws Exception {
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", key, value);
    }

    private void setResolvedProfile(SpringVersionProfile profile) throws Exception {
        Field field = AgentConfig.class.getDeclaredField("resolvedProfile");
        field.setAccessible(true);
        field.set(null, profile);
    }

    private void setResolvedProfileQuietly(SpringVersionProfile profile) {
        try {
            setResolvedProfile(profile);
        } catch (Exception ignored) {
        }
    }

    private ClassLoader selectiveLoader(String... loadableNames) {
        java.util.Set<String> names = java.util.Set.of(loadableNames);
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.startsWith("java.")) {
                    return super.loadClass(name);
                }
                if (names.contains(name)) {
                    return Object.class;
                }
                throw new ClassNotFoundException(name);
            }
        };
    }

}
