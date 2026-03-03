package org.example.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: ReactorContextHolder (Reactor Context 키 상수)")
class ReactorContextHolderTest {

    @Test
    @DisplayName("TX_ID_KEY 상수 값은 \"trace.txId\"이어야 한다")
    void txIdKey_hasExpectedValue() {
        assertEquals("trace.txId", ReactorContextHolder.TX_ID_KEY);
    }

    @Test
    @DisplayName("SPAN_ID_KEY 상수 값은 \"trace.spanId\"이어야 한다")
    void spanIdKey_hasExpectedValue() {
        assertEquals("trace.spanId", ReactorContextHolder.SPAN_ID_KEY);
    }
}
