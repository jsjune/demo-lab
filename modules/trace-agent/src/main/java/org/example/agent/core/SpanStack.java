package org.example.agent.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class SpanStack {
    private static final ThreadLocal<Deque<SpanContext>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    public static void push(String target, String category) {
        stack.get().push(new SpanContext(target, category, System.currentTimeMillis()));
    }

    public static Optional<SpanContext> pop() {
        Deque<SpanContext> deque = stack.get();
        if (deque.isEmpty()) return Optional.empty();
        return Optional.of(deque.pop());
    }

    public static Optional<SpanContext> peek() {
        Deque<SpanContext> deque = stack.get();
        if (deque.isEmpty()) return Optional.empty();
        return Optional.of(deque.peek());
    }

    public record SpanContext(String target, String category, long startTime) {}
}
