package org.example.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.TraceEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TraceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final String INSERT_SQL = 
        "INSERT INTO trace_events (event_id, tx_id, type, category, server_name, target, duration_ms, success, timestamp, extra_info) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) " +
        "ON CONFLICT (event_id, timestamp) DO NOTHING";

    @Transactional
    public void saveAll(List<TraceEvent> events) {
        if (events.isEmpty()) return;

        jdbcTemplate.batchUpdate(INSERT_SQL, events, events.size(), (ps, event) -> {
            ps.setString(1, event.eventId());
            ps.setString(2, event.txId());
            ps.setString(3, event.type() != null ? event.type().name() : null);
            ps.setString(4, event.category() != null ? event.category().name() : null);
            ps.setString(5, event.serverName());
            ps.setString(6, event.target());
            ps.setObject(7, event.durationMs());
            ps.setBoolean(8, event.success());
            ps.setLong(9, event.timestamp());
            try {
                ps.setString(10, objectMapper.writeValueAsString(event.extraInfo()));
            } catch (Exception e) {
                ps.setString(10, "{}");
            }
        });
        
        log.info("[TRACE BATCH] Persisted {} events to database", events.size());
    }
}
