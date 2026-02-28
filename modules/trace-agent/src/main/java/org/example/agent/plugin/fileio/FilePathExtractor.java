package org.example.agent.plugin.fileio;

import org.example.agent.plugin.ReflectionUtils;

/**
 * Extracts the file path from a {@code FileInputStream} or {@code FileOutputStream}
 * via reflection. Falls back to {@code "unknown-path"} on any failure.
 */
public class FilePathExtractor {

    private static final int MAX_PATH_LENGTH = 500;

    public static String extract(Object stream) {
        if (stream == null) return "unknown-path";
        return ReflectionUtils.getFieldValue(stream, "path")
            .map(Object::toString)
            .map(FilePathExtractor::truncate)
            .orElseGet(() -> truncate(stream.toString()));
    }

    private static String truncate(String path) {
        if (path == null) return "unknown-path";
        return path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) + "..." : path;
    }
}
