package org.example.batch;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trace.batch")
public class BatchProperties {
    private int batchSize = 1000;
    private long flushIntervalMs = 5000;
    private String topic = "trace-events";
}
