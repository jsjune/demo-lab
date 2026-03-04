package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoggerTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
        resetAgentLoggerState();
    }

    @AfterEach
    void tearDown() throws Exception {
        resetAgentLoggerState();
        System.clearProperty("trace.agent.debug");
        stateGuard.close();
    }

    @Test
    void init_debugLevel_setsTraceDebugSystemProperty() throws Exception {
        setConfig("log.level", "DEBUG");
        setConfig("log.file.path", tempLogPattern());
        setConfig("log.file.limit", "1048576");
        setConfig("log.file.count", "1");

        AgentLogger.init();

        assertEquals("true", System.getProperty("trace.agent.debug"));
    }

    @Test
    void init_createsMissingParentDirectory() throws Exception {
        Path dir = Path.of("modules", "trace-agent", "build", "tmp", "logger-" + UUID.randomUUID());
        Path pattern = dir.resolve("agent-%g.log");
        if (Files.exists(dir)) {
            fail("test directory collision: " + dir);
        }

        setConfig("log.level", "INFO");
        setConfig("log.file.path", pattern.toString());
        setConfig("log.file.limit", "1048576");
        setConfig("log.file.count", "1");

        AgentLogger.init();

        assertTrue(Files.exists(dir), "logger init should create parent directory for log file");
    }

    @Test
    void parseLevel_unknownValue_fallsBackToInfo() throws Exception {
        Method m = AgentLogger.class.getDeclaredMethod("parseLevel", String.class);
        m.setAccessible(true);
        Level level = (Level) m.invoke(null, "NOT_A_LEVEL");
        assertEquals(Level.INFO, level);
    }

    @Test
    void translateLevel_finerAndFine_areMapped() throws Exception {
        Method m = AgentLogger.class.getDeclaredMethod("translateLevel", Level.class);
        m.setAccessible(true);
        assertEquals("TRACE", m.invoke(null, Level.FINER));
        assertEquals("DEBUG", m.invoke(null, Level.FINE));
    }

    private void setConfig(String key, String value) throws Exception {
        Field f = AgentConfig.class.getDeclaredField("props");
        f.setAccessible(true);
        ((Properties) f.get(null)).setProperty(key, value);
    }

    private String tempLogPattern() {
        return Path.of("modules", "trace-agent", "build", "tmp", "agent-logger-" + UUID.randomUUID(), "trace-agent-%g.log").toString();
    }

    private void resetAgentLoggerState() throws Exception {
        Field loggerField = AgentLogger.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        Logger logger = (Logger) loggerField.get(null);
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
            try { h.close(); } catch (Exception ignored) {}
        }

        Field initializedField = AgentLogger.class.getDeclaredField("initialized");
        initializedField.setAccessible(true);
        initializedField.set(null, false);
    }
}
