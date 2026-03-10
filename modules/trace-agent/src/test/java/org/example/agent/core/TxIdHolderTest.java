package org.example.agent.core;

import org.example.agent.core.context.TxIdHolder;
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
    @DisplayName("자식 스레드는 부모의 txId를 상속받지 않아야 한다 (ThreadLocal — 스레드 풀 오염 방지)")
    void testNoInheritanceInChildThread() throws InterruptedException {
        String parentId = "parent-id-555";
        TxIdHolder.set(parentId);

        // AtomicReference로 자식 스레드 예외를 JUnit에 전파
        java.util.concurrent.atomic.AtomicReference<AssertionError> childError =
            new java.util.concurrent.atomic.AtomicReference<>();

        Thread childThread = new Thread(() -> {
            try {
                assertNull(TxIdHolder.get(), "Child thread must NOT inherit txId — ThreadLocal isolates threads");
                TxIdHolder.set("child-id-777");
                assertEquals("child-id-777", TxIdHolder.get(), "Child thread should be able to set its own txId");
            } catch (AssertionError e) {
                childError.set(e);
            }
        });

        childThread.start();
        childThread.join();

        if (childError.get() != null) throw childError.get();
        assertEquals(parentId, TxIdHolder.get(), "Parent thread's txId should remain unchanged");
    }
}
