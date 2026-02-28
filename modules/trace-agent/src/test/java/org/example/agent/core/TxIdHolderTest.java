package org.example.agent.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: TxIdHolder (트랜잭션 컨텍스트 저장소)")
class TxIdHolderTest {

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
    }

    @Test
    @DisplayName("트랜잭션 ID가 홀더에서 올바르게 조회되어야 한다")
    void testSetAndGet() {
        String testId = "test-id-123";
        TxIdHolder.set(testId);
        assertEquals(testId, TxIdHolder.get(), "TxId should be correctly retrieved from holder");
    }

    @Test
    @DisplayName("컨텍스트를 클리어한 후에는 트랜잭션 ID가 null이어야 한다")
    void testClear() {
        TxIdHolder.set("some-id");
        TxIdHolder.clear();
        assertNull(TxIdHolder.get(), "TxId should be null after clear()");
    }

    @Test
    @DisplayName("트랜잭션 ID는 자식 스레드에 상속되어야 한다 (InheritableThreadLocal)")
    void testInheritanceInChildThread() throws InterruptedException {
        String parentId = "parent-id-555";
        TxIdHolder.set(parentId);

        Thread childThread = new Thread(() -> {
            assertEquals(parentId, TxIdHolder.get(), "Child thread should inherit txId from parent (InheritableThreadLocal)");
            
            TxIdHolder.set("child-id-777");
            assertEquals("child-id-777", TxIdHolder.get(), "Child thread should be able to set its own txId");
        });

        childThread.start();
        childThread.join();

        assertEquals(parentId, TxIdHolder.get(), "Parent thread's txId should remain unchanged by child's modifications");
    }
}
