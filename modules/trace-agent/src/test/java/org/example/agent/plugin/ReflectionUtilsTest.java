package org.example.agent.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("유틸리티: ReflectionUtils (리플렉션 도구)")
class ReflectionUtilsTest {

    @Test
    @DisplayName("비공개 필드(private field)의 값을 올바르게 가져와야 한다")
    void testGetFieldValue() {
        DummyTarget target = new DummyTarget("secret-value");
        Optional<Object> value = ReflectionUtils.getFieldValue(target, "secretField");
        
        assertTrue(value.isPresent());
        assertEquals("secret-value", value.get());
    }

    @Test
    @DisplayName("존재하지 않는 필드 접근 시 Optional.empty()를 반환해야 한다")
    void testGetFieldValueMissing() {
        DummyTarget target = new DummyTarget("value");
        Optional<Object> value = ReflectionUtils.getFieldValue(target, "nonExistentField");
        
        assertFalse(value.isPresent());
    }

    @Test
    @DisplayName("인자가 없는 공개 메서드를 올바르게 호출해야 한다")
    void testInvokeMethodNoArgs() {
        DummyTarget target = new DummyTarget("value");
        Optional<Object> result = ReflectionUtils.invokeMethod(target, "publicMethod");
        
        assertTrue(result.isPresent());
        assertEquals("invoked", result.get());
    }

    @Test
    @DisplayName("인자가 있는 공개 메서드를 올바르게 호출해야 한다")
    void testInvokeMethodWithArgs() {
        DummyTarget target = new DummyTarget("value");
        Optional<Object> result = ReflectionUtils.invokeMethod(target, "methodWithArg", "hello");
        
        assertTrue(result.isPresent());
        assertEquals("hello-processed", result.get());
    }

    @Test
    @DisplayName("존재하지 않는 메서드 호출 시 Optional.empty()를 반환해야 한다")
    void testInvokeMethodMissing() {
        DummyTarget target = new DummyTarget("value");
        Optional<Object> result = ReflectionUtils.invokeMethod(target, "nonExistentMethod");
        
        assertFalse(result.isPresent());
    }

    static class DummyTarget {
        private final String secretField;

        DummyTarget(String secretField) {
            this.secretField = secretField;
        }

        public String publicMethod() {
            return "invoked";
        }

        public String methodWithArg(String input) {
            return input + "-processed";
        }
    }
}
