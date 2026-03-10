package org.example.agent.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard: plugin sources must not directly reference core internal implementations
 * (TcpSender, emitter internals, *EventHandler classes).
 * All event emission must go through TraceRuntime (the public ABI).
 */
@DisplayName("아키텍처: 플러그인 → 코어 내부 직접 참조 금지")
class DependencyTest {

    /** Patterns whose presence in any plugin .java file is a dependency violation. */
    private static final List<String> FORBIDDEN_PATTERNS = List.of(
        "org.example.agent.core.emitter.TcpSender",
        "org.example.agent.core.emitter.TcpSenderEmitter",
        "org.example.agent.core.emitter.EventEmitter",
        "org.example.agent.core.handler."
    );

    @Test
    @DisplayName("플러그인 소스 파일이 코어 내부 구현(TcpSender, *EventHandler)을 직접 참조하지 않아야 한다")
    void pluginSources_mustNotReferenceInternalCore() throws IOException {
        Path pluginSrcRoot = Paths.get(System.getProperty("user.dir"),
            "src/main/java/org/example/agent/plugin");

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(pluginSrcRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        for (String forbidden : FORBIDDEN_PATTERNS) {
                            if (content.contains(forbidden)) {
                                violations.add(file.getFileName() + " → \"" + forbidden + "\"");
                            }
                        }
                    } catch (IOException e) {
                        violations.add("UNREADABLE: " + file);
                    }
                });
        }

        assertTrue(violations.isEmpty(),
            "Plugin sources must not reference core internals.\nViolations:\n  " +
            String.join("\n  ", violations));
    }
}
