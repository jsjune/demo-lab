package org.example.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpringVersionProfileTest {

    @Test
    void fromConfig_resolvesKnownVersions() {
        assertEquals(SpringVersionProfile.SPRING_5, SpringVersionProfile.fromConfig("5.3.33"));
        assertEquals(SpringVersionProfile.SPRING_6_0, SpringVersionProfile.fromConfig("6.0.15"));
        assertEquals(SpringVersionProfile.SPRING_6_1, SpringVersionProfile.fromConfig("6.1.7"));
        assertEquals(SpringVersionProfile.SPRING_6_1, SpringVersionProfile.fromConfig("7.0.0-M1"));
        assertEquals(SpringVersionProfile.SPRING_5, SpringVersionProfile.fromConfig(null));
    }

    @Test
    void detect_returnsNullWhenLoaderIsNull() {
        assertNull(SpringVersionProfile.detect(null));
    }

    @Test
    void detect_spring5WhenOnlyJavaxIsLoadable() {
        ClassLoader loader = selectiveLoader("javax.servlet.http.HttpServletRequest");
        assertEquals(SpringVersionProfile.SPRING_5, SpringVersionProfile.detect(loader));
    }

    @Test
    void detect_spring60WhenJakartaWithoutRestClient() {
        ClassLoader loader = selectiveLoader("jakarta.servlet.http.HttpServletRequest");
        assertEquals(SpringVersionProfile.SPRING_6_0, SpringVersionProfile.detect(loader));
    }

    @Test
    void detect_spring61WhenJakartaAndRestClientAreLoadable() {
        ClassLoader loader = selectiveLoader(
            "jakarta.servlet.http.HttpServletRequest",
            "org.springframework.web.client.RestClient");
        assertEquals(SpringVersionProfile.SPRING_6_1, SpringVersionProfile.detect(loader));
    }

    @Test
    void detect_returnsNullWhenServletApisAreNotLoadable() {
        ClassLoader loader = selectiveLoader("com.example.Unrelated");
        assertNull(SpringVersionProfile.detect(loader));
    }

    private ClassLoader selectiveLoader(String... loadableNames) {
        java.util.Set<String> names = java.util.Set.of(loadableNames);
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (names.contains(name)) {
                    return Object.class;
                }
                throw new ClassNotFoundException(name);
            }
        };
    }
}
