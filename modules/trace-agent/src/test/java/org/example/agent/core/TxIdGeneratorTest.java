package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: TxIdGenerator (ID 생성 및 샘플링)")
class TxIdGeneratorTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
    }

    @AfterEach
    void tearDown() {
        stateGuard.close();
    }

    @Test
    @DisplayName("생성된 트랜잭션 ID는 서버 이름을 포함해야 한다")
    void testGenerateFormat() {
        String txId = TxIdGenerator.generate();
        assertNotNull(txId);
        
        // server-name from trace-agent.properties is 'trace-agent-node'
        assertTrue(txId.contains("trace-agent-node"), "Generated txId should contain server name");
        
        String[] parts = txId.split("-");
        assertTrue(parts.length >= 3, "TxId should have at least 3 parts (server-timestamp-random)");
    }

    @Test
    @DisplayName("기본 샘플링 결정은 '항상 샘플링'이어야 한다")
    void testShouldSample() {
        // By default, sampling rate is 1.0 (always sample)
        assertTrue(TxIdGenerator.shouldSample(), "Default sampling rate 1.0 should always sample");
    }

    @Test
    @DisplayName("생성된 트랜잭션 ID는 전역적으로 유일해야 한다")
    void testGenerateUniqueness() {
        String id1 = TxIdGenerator.generate();
        String id2 = TxIdGenerator.generate();
        assertNotEquals(id1, id2, "Generated IDs should be unique");
    }

    @Test
    @DisplayName("sampling.strategy=error-only면 항상 샘플링")
    void shouldSample_errorOnly_alwaysTrue() {
        stateGuard.setSystemProperty("trace.agent.sampling.strategy", "error-only");
        AgentConfig.init();
        assertTrue(TxIdGenerator.shouldSample());
    }

    @Test
    @DisplayName("sampling.rate=0이면 샘플링하지 않음")
    void shouldSample_rateZero_alwaysFalse() {
        stateGuard.setSystemProperty("trace.agent.sampling.strategy", "rate");
        stateGuard.setSystemProperty("trace.agent.sampling.rate", "0");
        AgentConfig.init();
        assertFalse(TxIdGenerator.shouldSample());
    }

    @Test
    @DisplayName("sampling.rate=2.0이면 상한 처리로 항상 샘플링")
    void shouldSample_rateAboveOne_alwaysTrue() {
        stateGuard.setSystemProperty("trace.agent.sampling.strategy", "rate");
        stateGuard.setSystemProperty("trace.agent.sampling.rate", "2.0");
        AgentConfig.init();
        assertTrue(TxIdGenerator.shouldSample());
    }
}
