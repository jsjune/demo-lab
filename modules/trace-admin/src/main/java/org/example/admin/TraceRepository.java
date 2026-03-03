package org.example.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class TraceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private RowMapper<TraceEvent> rowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> extraInfo = new HashMap<>();
            String extraJson = rs.getString("extra_info");
            if (extraJson != null && !extraJson.isEmpty()) {
                try {
                    extraInfo = objectMapper.readValue(extraJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception ignored) {}
            }
            return new TraceEvent(
                    rs.getString("event_id"),
                    rs.getString("tx_id"),
                    rs.getString("span_id"),
                    rs.getString("parent_span_id"),
                    TraceEventType.valueOf(rs.getString("type")),
                    TraceCategory.valueOf(rs.getString("category")),
                    rs.getString("server_name"),
                    rs.getString("target"),
                    rs.getObject("duration_ms") != null ? rs.getLong("duration_ms") : null,
                    rs.getBoolean("success"),
                    rs.getLong("timestamp"),
                    extraInfo
            );
        };
    }

    public List<TraceEvent> findTraces(String txId, String serverName) {
        StringBuilder sql = new StringBuilder("SELECT * FROM trace_events WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (txId != null && !txId.isEmpty()) {
            sql.append("AND tx_id = ? ");
            params.add(txId);
        }
        if (serverName != null && !serverName.isEmpty()) {
            sql.append("AND server_name = ? ");
            params.add(serverName);
        }

        sql.append("ORDER BY timestamp DESC LIMIT 100");
        return jdbcTemplate.query(sql.toString(), rowMapper(), params.toArray());
    }

    public List<TraceEvent> findByTxId(String txId) {
        String sql = "SELECT * FROM trace_events WHERE tx_id = ? ORDER BY timestamp ASC";
        return jdbcTemplate.query(sql, rowMapper(), txId);
    }

    public List<ServiceLink> findServiceLinks() {
        String sql = "SELECT DISTINCT " +
                     "  out_ev.server_name as caller, " +
                     "  COALESCE(in_ev.server_name, out_ev.category) as callee, " +
                     "  out_ev.category as type " +
                     "FROM trace_events out_ev " +
                     "LEFT JOIN trace_events in_ev ON out_ev.tx_id = in_ev.tx_id " +
                     "  AND ((out_ev.type = 'HTTP_OUT' AND in_ev.type = 'HTTP_IN_START') " +
                     "       OR (out_ev.type = 'MQ_PRODUCE' AND in_ev.type = 'MQ_CONSUME_START')) " +
                     "WHERE out_ev.type IN ('HTTP_OUT', 'MQ_PRODUCE', 'DB_QUERY_START', 'CACHE_HIT', 'CACHE_SET')";
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ServiceLink(
                rs.getString("caller"),
                rs.getString("callee"),
                rs.getString("type")
        ));
    }

    public record ServiceLink(String caller, String callee, String type) {}

    // Dashboard Metrics
    public record MetricPoint(long timestamp, double value) {}

    public List<MetricPoint> getTpsPoints(int minutes, String serverName) {
        long startTime = System.currentTimeMillis() - (minutes * 60 * 1000L);
        StringBuilder sql = new StringBuilder("SELECT (timestamp / 60000) * 60000 AS bucket, COUNT(*) AS val ")
                .append("FROM trace_events WHERE type = 'HTTP_IN_START' AND timestamp >= ? ");
        List<Object> params = new ArrayList<>();
        params.add(startTime);
        
        if (serverName != null && !serverName.isEmpty()) {
            sql.append("AND server_name = ? ");
            params.add(serverName);
        }
        sql.append("GROUP BY bucket ORDER BY bucket ASC");
        
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new MetricPoint(
                rs.getLong("bucket"),
                rs.getDouble("val")
        ), params.toArray());
    }

    public List<MetricPoint> getLatencyPoints(int minutes, String serverName) {
        long startTime = System.currentTimeMillis() - (minutes * 60 * 1000L);
        StringBuilder sql = new StringBuilder("SELECT (timestamp / 60000) * 60000 AS bucket, AVG(duration_ms) AS val ")
                .append("FROM trace_events WHERE type = 'HTTP_IN_END' AND timestamp >= ? ");
        List<Object> params = new ArrayList<>();
        params.add(startTime);

        if (serverName != null && !serverName.isEmpty()) {
            sql.append("AND server_name = ? ");
            params.add(serverName);
        }
        sql.append("GROUP BY bucket ORDER BY bucket ASC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new MetricPoint(
                rs.getLong("bucket"),
                rs.getDouble("val")
        ), params.toArray());
    }

    public List<MetricPoint> getErrorPoints(int minutes, String serverName) {
        long startTime = System.currentTimeMillis() - (minutes * 60 * 1000L);
        StringBuilder sql = new StringBuilder("SELECT (timestamp / 60000) * 60000 AS bucket, COUNT(*) AS val ")
                .append("FROM trace_events WHERE type = 'HTTP_IN_END' AND success = false AND timestamp >= ? ");
        List<Object> params = new ArrayList<>();
        params.add(startTime);

        if (serverName != null && !serverName.isEmpty()) {
            sql.append("AND server_name = ? ");
            params.add(serverName);
        }
        sql.append("GROUP BY bucket ORDER BY bucket ASC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new MetricPoint(
                rs.getLong("bucket"),
                rs.getDouble("val")
        ), params.toArray());
    }

    public List<TraceEvent> findSlowestTraces(int minutes, String serverName, int limit) {
        long startTime = System.currentTimeMillis() - (minutes * 60 * 1000L);
        StringBuilder sql = new StringBuilder("SELECT * FROM trace_events WHERE type = 'HTTP_IN_END' AND timestamp >= ? ");
        List<Object> params = new ArrayList<>();
        params.add(startTime);

        if (serverName != null && !serverName.isEmpty()) {
            sql.append("AND server_name = ? ");
            params.add(serverName);
        }
        sql.append("ORDER BY duration_ms DESC LIMIT ?");
        params.add(limit);

        return jdbcTemplate.query(sql.toString(), rowMapper(), params.toArray());
    }

    // Alert Rule Management
    public record AlertRule(Long id, String name, String category, String thresholdType, double thresholdValue, boolean enabled) {}

    public List<AlertRule> findAllAlertRules() {
        return jdbcTemplate.query("SELECT * FROM alert_rules", (rs, rowNum) -> new AlertRule(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("threshold_type"),
                rs.getDouble("threshold_value"),
                rs.getBoolean("enabled")
        ));
    }

    public void saveAlertRule(AlertRule rule) {
        if (rule.id() == null) {
            jdbcTemplate.update("INSERT INTO alert_rules (name, category, threshold_type, threshold_value, enabled) VALUES (?, ?, ?, ?, ?)",
                    rule.name(), rule.category(), rule.thresholdType(), rule.thresholdValue(), rule.enabled());
        } else {
            jdbcTemplate.update("UPDATE alert_rules SET name = ?, category = ?, threshold_type = ?, threshold_value = ?, enabled = ? WHERE id = ?",
                    rule.name(), rule.category(), rule.thresholdType(), rule.thresholdValue(), rule.enabled(), rule.id());
        }
    }

    public void deleteAlertRule(Long id) {
        jdbcTemplate.update("DELETE FROM alert_rules WHERE id = ?", id);
    }
}
