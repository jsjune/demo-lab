package org.example.agent.core;

import org.example.agent.config.AgentConfig;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Internal logger for the trace-agent.
 * Uses java.util.logging to avoid dependencies.
 */
public class AgentLogger {
    private static final Logger logger = Logger.getLogger("org.example.agent");
    private static boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;

        try {
            // 1. Set Level
            String levelStr = AgentConfig.getLogLevel();
            Level level = parseLevel(levelStr);
            logger.setLevel(level);
            logger.setUseParentHandlers(false);

            // If level is debug (FINE), ensure the system property is set for other components
            if (level.intValue() <= Level.FINE.intValue()) {
                System.setProperty("trace.agent.debug", "true");
            }

            // 2. Setup File Handler
            String pattern = AgentConfig.getLogFilePath();
            ensureDirectory(pattern);

            FileHandler fileHandler = getFileHandler(pattern, level);
            logger.addHandler(fileHandler);

            initialized = true;
            logger.info("Agent Logger initialized (Level: " + levelStr + "). Log file: " + pattern);

        } catch (Exception e) {
            System.err.println("[TRACE AGENT] Failed to initialize file logger: " + e.getMessage());
        }
    }

    private static FileHandler getFileHandler(String pattern, Level level) throws IOException {
        FileHandler fileHandler = new FileHandler(
                pattern,
            AgentConfig.getLogFileLimit(),
            AgentConfig.getLogFileCount(),
            true
        );

        Formatter formatter = new Formatter() {
            private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            @Override
            public String format(LogRecord record) {
                return String.format("[%s] [%-7s] %s%n",
                    sdf.format(new Date(record.getMillis())),
                    translateLevel(record.getLevel()),
                    record.getMessage()
                );
            }
        };

        fileHandler.setFormatter(formatter);
        fileHandler.setLevel(level);
        return fileHandler;
    }

    private static String translateLevel(Level level) {
        if (level.intValue() == Level.FINE.intValue()) return "DEBUG";
        if (level.intValue() == Level.FINER.intValue()) return "TRACE";
        if (level.intValue() == Level.FINEST.intValue()) return "TRACE";
        return level.getName();
    }

    public static void info(String msg) {
        logger.info(msg);
    }

    public static void warn(String msg) {
        logger.warning(msg);
    }

    public static void error(String msg, Throwable t) {
        logger.log(Level.SEVERE, msg, t);
    }

    public static void debug(String msg) {
        logger.fine(msg);
    }

    private static Level parseLevel(String levelName) {
        if (levelName == null) return Level.INFO;
        // Map common log framework names to java.util.logging levels
        switch (levelName.toUpperCase()) {
            case "DEBUG": return Level.FINE;
            case "TRACE": return Level.FINER;
            case "WARN":  return Level.WARNING;
            case "ERROR": return Level.SEVERE;
            default:
                try { return Level.parse(levelName.toUpperCase()); } catch (Exception e) { return Level.INFO; }
        }
    }

    private static void ensureDirectory(String filePath) {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }
}
