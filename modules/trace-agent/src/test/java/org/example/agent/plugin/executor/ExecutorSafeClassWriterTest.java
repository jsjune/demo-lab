package org.example.agent.plugin.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Executor SafeClassWriter.getCommonSuperClass 테스트")
class ExecutorSafeClassWriterTest {

    private ExecutorPlugin.SafeClassWriter newWriter(ClassLoader loader) throws Exception {
        ClassReader cr = new ClassReader(ExecutorSafeClassWriterTest.class.getName());
        return new ExecutorPlugin.SafeClassWriter(cr, loader);
    }

    @Test
    @DisplayName("계층 타입이면 공통 슈퍼클래스를 반환")
    void commonSuperClass_forJdkTypes() throws Exception {
        ExecutorPlugin.SafeClassWriter writer = newWriter(getClass().getClassLoader());
        String out = writer.getCommonSuperClass("java/util/ArrayList", "java/util/LinkedList");
        assertEquals("java/util/AbstractList", out);
    }

    @Test
    @DisplayName("인터페이스 조합이면 Object 반환")
    void commonSuperClass_forInterfaces_returnsObject() throws Exception {
        ExecutorPlugin.SafeClassWriter writer = newWriter(getClass().getClassLoader());
        String out = writer.getCommonSuperClass("java/util/List", "java/util/Map");
        assertEquals("java/lang/Object", out);
    }

    @Test
    @DisplayName("알 수 없는 타입 로딩 실패 시 Object로 폴백")
    void commonSuperClass_unknownTypes_fallbackObject() throws Exception {
        ClassLoader empty = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
        ExecutorPlugin.SafeClassWriter writer = newWriter(empty);
        String out = writer.getCommonSuperClass("x/UnknownA", "x/UnknownB");
        assertEquals("java/lang/Object", out);
    }
}

