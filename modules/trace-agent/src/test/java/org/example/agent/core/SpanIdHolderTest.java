package org.example.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: SpanIdHolder (루트 Span ID ThreadLocal 관리)")
class SpanIdHolderTest {

    @BeforeEach
    void setUp() {
        SpanIdHolder.clear();
    }

    @AfterEach
    void tearDown() {
        SpanIdHolder.clear();
    }

    @Test
    @DisplayName("set() 후 get()은 설정한 값을 반환해야 한다")
    void set_and_get_returnsSetValue() {
        SpanIdHolder.set("span-123");
        assertEquals("span-123", SpanIdHolder.get());
    }

    @Test
    @DisplayName("초기 상태에서 get()은 null을 반환해야 한다")
    void initialState_returnsNull() {
        assertNull(SpanIdHolder.get());
    }

    @Test
    @DisplayName("set() 후 clear()를 호출하면 get()은 null을 반환해야 한다")
    void clear_afterSet_returnsNull() {
        SpanIdHolder.set("span-456");
        SpanIdHolder.clear();
        assertNull(SpanIdHolder.get());
    }

    @Test
    @DisplayName("다른 스레드에서의 SpanId는 현재 스레드에 영향을 주지 않아야 한다 (ThreadLocal 격리)")
    void threadIsolation_differentThreadsHaveIndependentValues() throws InterruptedException {
        SpanIdHolder.set("main-span");

        AtomicReference<String> threadValue = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread other = new Thread(() -> {
            SpanIdHolder.set("other-span");
            threadValue.set(SpanIdHolder.get());
            SpanIdHolder.clear();
            latch.countDown();
        });
        other.start();
        latch.await();

        assertEquals("main-span", SpanIdHolder.get(),
                "다른 스레드 set()이 현재 스레드에 영향을 주지 않아야 한다");
        assertEquals("other-span", threadValue.get(),
                "다른 스레드는 자신이 설정한 값을 읽어야 한다");
    }

    @Test
    @DisplayName("set()을 여러 번 호출하면 마지막 값이 반환되어야 한다")
    void multipleSet_returnsLastValue() {
        SpanIdHolder.set("first");
        SpanIdHolder.set("second");
        SpanIdHolder.set("third");
        assertEquals("third", SpanIdHolder.get());
    }
}
