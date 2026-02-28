package org.example.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("TraceEvent는 category 필드를 포함하여 NDJSON으로 직렬화된다")
    void testSerialization() throws JsonProcessingException {
        TraceEvent event = new TraceEvent(
            "e1", "t1", "s1", TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "server1", "target1", null, true, System.currentTimeMillis(), new HashMap<>()
        );

        String json = objectMapper.writeValueAsString(event);
        assertTrue(json.contains("\"category\":\"HTTP\""));
        assertTrue(json.contains("\"eventId\":\"e1\""));
        assertTrue(json.contains("\"spanId\":\"s1\""));
    }

    @Test
    @DisplayName("durationMs는 START 이벤트에서 null이다")
    void testDurationMsNull() {
        TraceEvent event = new TraceEvent(
            "e1", "t1", "s1", TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "server1", "target1", null, true, System.currentTimeMillis(), new HashMap<>()
        );

        assertNull(event.durationMs());
    }
}
