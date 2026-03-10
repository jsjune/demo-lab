package org.example.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies shadowJar correctness:
 *  1) All 5 TracerPlugin SPI entries present
 *  2) Jackson, ByteBuddy, trace-common properly relocated to org.example.agent.shaded.*
 *  3) No unrelocated net.bytebuddy / com.fasterxml.jackson / org.example.common in jar
 */
@DisplayName("아키텍처: shadowJar 구조 및 재배치 검증")
class ShadowJarVerificationTest {

    private static Set<String> jarEntries;
    private static JarFile jarFile;
    private static final String SPI_ENTRY = "META-INF/services/org.example.agent.TracerPlugin";

    @BeforeAll
    static void loadJar() throws IOException {
        // Locate the shadow JAR relative to project root (user.dir in Gradle multi-project)
        Path jarPath = Paths.get(System.getProperty("user.dir"),
            "build/libs/trace-agent-0.0.1-SNAPSHOT.jar");
        if (!Files.exists(jarPath)) {
            // Fallback: module working directory
            jarPath = Paths.get(System.getProperty("user.dir"),
                "modules/trace-agent/build/libs/trace-agent-0.0.1-SNAPSHOT.jar");
        }
        assertTrue(Files.exists(jarPath),
            "shadowJar not found — run './gradlew :modules:trace-agent:shadowJar' first. Looked at: " + jarPath);
        jarFile = new JarFile(jarPath.toFile());
        jarEntries = jarFile.stream()
            .map(JarEntry::getName)
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("SPI-01: META-INF/services/org.example.agent.TracerPlugin 파일 존재")
    void spiFileExists() {
        assertTrue(jarEntries.contains(SPI_ENTRY),
            "SPI file missing: " + SPI_ENTRY);
    }

    @Test
    @DisplayName("SPI-02: 5개 플러그인 모두 SPI에 등록됨")
    void spiContainsAllPlugins() throws IOException {
        JarEntry entry = jarFile.getJarEntry(SPI_ENTRY);
        assertNotNull(entry);
        String content;
        try (InputStream is = jarFile.getInputStream(entry)) {
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> expected = List.of(
            "org.example.agent.plugin.http.HttpPlugin",
            "org.example.agent.plugin.jdbc.JdbcPlugin",
            "org.example.agent.plugin.mq.KafkaPlugin",
            "org.example.agent.plugin.cache.RedisPlugin",
            "org.example.agent.plugin.executor.ExecutorPlugin"
        );
        for (String plugin : expected) {
            assertTrue(content.contains(plugin), "Missing plugin in SPI: " + plugin);
        }
    }

    @Test
    @DisplayName("RELOC-01: ByteBuddy가 org.example.agent.shaded.bytebuddy로 재배치됨")
    void byteBuddyRelocated() {
        boolean shadedExists = jarEntries.stream()
            .anyMatch(e -> e.startsWith("org/example/agent/shaded/bytebuddy/"));
        assertTrue(shadedExists, "Shaded ByteBuddy classes not found under org.example.agent.shaded.bytebuddy");
    }

    @Test
    @DisplayName("RELOC-02: Jackson이 org.example.agent.shaded.jackson으로 재배치됨")
    void jacksonRelocated() {
        boolean shadedExists = jarEntries.stream()
            .anyMatch(e -> e.startsWith("org/example/agent/shaded/jackson/"));
        assertTrue(shadedExists, "Shaded Jackson classes not found under org.example.agent.shaded.jackson");
    }

    @Test
    @DisplayName("RELOC-03: trace-common이 org.example.agent.shaded.common으로 재배치됨")
    void traceCommonRelocated() {
        boolean shadedExists = jarEntries.stream()
            .anyMatch(e -> e.startsWith("org/example/agent/shaded/common/"));
        assertTrue(shadedExists, "Shaded trace-common classes not found under org.example.agent.shaded.common");
    }

    @Test
    @DisplayName("RELOC-04: 원본 net.bytebuddy 패키지가 JAR에 없음 (재배치 완료)")
    void originalByteBuddyAbsent() {
        boolean unrelocated = jarEntries.stream()
            .anyMatch(e -> e.startsWith("net/bytebuddy/"));
        assertFalse(unrelocated, "Unrelocated net.bytebuddy entries found — relocation failed");
    }

    @Test
    @DisplayName("RELOC-05: 원본 com.fasterxml.jackson 패키지가 JAR에 없음 (재배치 완료)")
    void originalJacksonAbsent() {
        boolean unrelocated = jarEntries.stream()
            .anyMatch(e -> e.startsWith("com/fasterxml/jackson/"));
        assertFalse(unrelocated, "Unrelocated com.fasterxml.jackson entries found — relocation failed");
    }

    @Test
    @DisplayName("RELOC-06: 원본 org.example.common 패키지가 JAR에 없음 (재배치 완료)")
    void originalTraceCommonAbsent() {
        boolean unrelocated = jarEntries.stream()
            .anyMatch(e -> e.startsWith("org/example/common/"));
        assertFalse(unrelocated, "Unrelocated org.example.common entries found — relocation failed");
    }
}
