package org.example.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEvent(
    String eventId,
    String txId,
    TraceEventType type,
    TraceCategory category,
    String serverName,
    String target,
    Long durationMs,
    boolean success,
    long timestamp,
    Map<String, Object> extraInfo
) {
}
