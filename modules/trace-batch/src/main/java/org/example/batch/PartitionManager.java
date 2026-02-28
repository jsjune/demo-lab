package org.example.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionManager {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs every hour to ensure partitions for today and tomorrow exist.
     */
    @Scheduled(fixedDelay = 3600000)
    public void ensurePartitions() {
        createPartitionForDate(LocalDate.now());
        createPartitionForDate(LocalDate.now().plusDays(1));
    }

    private void createPartitionForDate(LocalDate date) {
        String partitionName = "trace_events_" + date.toString().replace("-", "_");
        long start = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        long end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();

        String sql = String.format(
            "CREATE TABLE IF NOT EXISTS %s PARTITION OF trace_events " +
            "FOR VALUES FROM (%d) TO (%d)", 
            partitionName, start, end
        );

        try {
            jdbcTemplate.execute(sql);
            log.debug("[PARTITION MANAGER] Ensured partition exists: {}", partitionName);
        } catch (Exception e) {
            log.error("[PARTITION MANAGER] Failed to create partition {}: {}", partitionName, e.getMessage());
        }
    }
}
