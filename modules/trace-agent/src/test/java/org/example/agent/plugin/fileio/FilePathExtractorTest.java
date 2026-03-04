package org.example.agent.plugin.fileio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FilePathExtractorTest {

    @Test
    void extract_nullStream_returnsUnknownPath() {
        assertEquals("unknown-path", FilePathExtractor.extract(null));
    }

    @Test
    void extract_withPathField_returnsPath() {
        PathStream s = new PathStream("/tmp/a.log");
        assertEquals("/tmp/a.log", FilePathExtractor.extract(s));
    }

    @Test
    void extract_withoutPathField_fallbacksToToString() {
        Object s = new Object() {
            @Override
            public String toString() {
                return "fallback-stream";
            }
        };
        assertEquals("fallback-stream", FilePathExtractor.extract(s));
    }

    @Test
    void extract_truncatesLongPath() {
        String longPath = "x".repeat(700);
        PathStream s = new PathStream(longPath);
        String out = FilePathExtractor.extract(s);
        assertTrue(out.length() <= 503);
        assertTrue(out.endsWith("..."));
    }

    static final class PathStream {
        @SuppressWarnings("unused")
        private final String path;

        private PathStream(String path) {
            this.path = path;
        }
    }
}
