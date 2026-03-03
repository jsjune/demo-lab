package org.example.agent.plugin.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 단위 테스트: SafeClassWriter.getCommonSuperClass() 타입 해석 동작 검증.
 *
 * <p>SafeClassWriter Javadoc에 명시된 두 가지 계약을 검증한다:
 * <ol>
 *   <li>알 수 없는 타입에 대해 "java/lang/Object"로 안전하게 폴백한다.</li>
 *   <li>앱 클래스로더를 통해 타입 계층을 올바르게 해석한다.</li>
 * </ol>
 *
 * <p>SafeClassWriter는 package-private이므로 같은 패키지에 위치한다.
 */
@DisplayName("단위: SafeClassWriter.getCommonSuperClass() 타입 해석 검증")
class SafeClassWriterTest {

    /**
     * SafeClassWriter 인스턴스를 생성한다.
     * ClassReader 시드로는 이 테스트 클래스 자신의 바이트를 사용한다.
     */
    private HttpPlugin.SafeClassWriter newWriter(ClassLoader loader) throws IOException {
        ClassReader cr = new ClassReader(SafeClassWriterTest.class.getName());
        return new HttpPlugin.SafeClassWriter(cr, loader);
    }

    // -----------------------------------------------------------------------
    // 타입 계층 해석 — 올바른 공통 슈퍼클래스 반환
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("type1이 type2의 슈퍼타입이면 type1을 반환해야 한다")
    void getCommonSuperClass_type1IsSupertypeOfType2_returnsType1() throws IOException {
        // AbstractList.isAssignableFrom(ArrayList) = true → returns type1
        HttpPlugin.SafeClassWriter writer = newWriter(getClass().getClassLoader());
        String result = writer.getCommonSuperClass(
                "java/util/AbstractList", "java/util/ArrayList");
        assertEquals("java/util/AbstractList", result);
    }

    @Test
    @DisplayName("type2가 type1의 슈퍼타입이면 type2를 반환해야 한다")
    void getCommonSuperClass_type2IsSupertypeOfType1_returnsType2() throws IOException {
        // ArrayList.isAssignableFrom(AbstractList) = false
        // AbstractList.isAssignableFrom(ArrayList) = true → returns type2
        HttpPlugin.SafeClassWriter writer = newWriter(getClass().getClassLoader());
        String result = writer.getCommonSuperClass(
                "java/util/ArrayList", "java/util/AbstractList");
        assertEquals("java/util/AbstractList", result);
    }

    @Test
    @DisplayName("관계 없는 두 구체 클래스의 공통 슈퍼클래스를 반환해야 한다")
    void getCommonSuperClass_unrelatedConcreteClasses_returnsCommonParent() throws IOException {
        // ArrayList → AbstractList → AbstractCollection → Object
        // LinkedList → AbstractSequentialList → AbstractList
        // 공통 슈퍼클래스: AbstractList
        HttpPlugin.SafeClassWriter writer = newWriter(getClass().getClassLoader());
        String result = writer.getCommonSuperClass(
                "java/util/ArrayList", "java/util/LinkedList");
        assertEquals("java/util/AbstractList", result);
    }

    // -----------------------------------------------------------------------
    // 인터페이스 처리
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("관계 없는 두 인터페이스는 java/lang/Object를 반환해야 한다")
    void getCommonSuperClass_unrelatedInterfaces_returnsObject() throws IOException {
        // List.isAssignableFrom(Map) = false, Map.isAssignableFrom(List) = false
        // c.isInterface() = true → returns "java/lang/Object"
        HttpPlugin.SafeClassWriter writer = newWriter(getClass().getClassLoader());
        String result = writer.getCommonSuperClass("java/util/List", "java/util/Map");
        assertEquals("java/lang/Object", result);
    }

    // -----------------------------------------------------------------------
    // 폴백: 알 수 없는 타입 → java/lang/Object
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("알 수 없는 타입에 대해 java/lang/Object로 안전하게 폴백해야 한다 (ClassNotFoundException 방어)")
    void getCommonSuperClass_unknownTypes_fallsBackToObject() throws IOException {
        // 어떤 클래스도 로드하지 못하는 빈 ClassLoader
        ClassLoader emptyLoader = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
        HttpPlugin.SafeClassWriter writer = newWriter(emptyLoader);

        // 예외가 발생하지 않아야 하며 "java/lang/Object"를 반환해야 한다
        String result = writer.getCommonSuperClass("unknown/TypeA", "unknown/TypeB");
        assertEquals("java/lang/Object", result);
    }

    // -----------------------------------------------------------------------
    // null ClassLoader 처리
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("null ClassLoader 전달 시 시스템 클래스로더로 폴백하여 JDK 타입을 해석해야 한다")
    void constructor_nullClassLoader_fallsBackToSystemClassLoaderAndResolvesJdkTypes()
            throws IOException {
        // SafeClassWriter(cr, null) → this.loader = ClassLoader.getSystemClassLoader()
        HttpPlugin.SafeClassWriter writer = newWriter(null);

        // 시스템 클래스로더는 JDK 타입을 볼 수 있어야 한다
        String result = writer.getCommonSuperClass(
                "java/util/ArrayList", "java/util/LinkedList");
        assertEquals("java/util/AbstractList", result);
    }
}