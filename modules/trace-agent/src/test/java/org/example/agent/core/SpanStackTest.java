package org.example.agent.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: SpanStack (컨텍스트 관리 스택)")
class SpanStackTest {

    @BeforeEach
    void setUp() {
        // Clear the stack before each test
        while (SpanStack.pop().isPresent());
    }

    @Test
    @DisplayName("Push와 Pop 동작은 LIFO(후입선출) 순서를 유지해야 한다")
    void testPushAndPop() {
        SpanStack.push("target1", "category1");
        SpanStack.push("target2", "category2");

        Optional<SpanStack.SpanContext> pop2 = SpanStack.pop();
        assertTrue(pop2.isPresent());
        assertEquals("target2", pop2.get().target());

        Optional<SpanStack.SpanContext> pop1 = SpanStack.pop();
        assertTrue(pop1.isPresent());
        assertEquals("target1", pop1.get().target());

        assertFalse(SpanStack.pop().isPresent());
    }

    @Test
    @DisplayName("Peek 동작은 요소를 제거하지 않고 최상단 요소를 반환해야 한다")
    void testPeek() {
        SpanStack.push("target1", "category1");
        
        Optional<SpanStack.SpanContext> peek1 = SpanStack.peek();
        assertTrue(peek1.isPresent());
        assertEquals("target1", peek1.get().target());

        // Peek should not remove the element
        assertEquals("target1", SpanStack.peek().get().target());
    }

    @Test
    @DisplayName("Span 컨텍스트는 스레드별로 격리되어야 한다")
    void testMultiThreading() throws InterruptedException {
        SpanStack.push("main-thread", "main");

        Thread t1 = new Thread(() -> {
            assertFalse(SpanStack.pop().isPresent(), "New thread should have an empty stack");
            SpanStack.push("t1-thread", "t1");
            assertEquals("t1-thread", SpanStack.pop().get().target());
        });

        t1.start();
        t1.join();

        assertEquals("main-thread", SpanStack.pop().get().target(), "Main thread stack should be untouched");
    }
}
