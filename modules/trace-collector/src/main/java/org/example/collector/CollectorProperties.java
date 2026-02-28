package org.example.collector;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ConfigurationProperties(prefix = "collector")
public class CollectorProperties {
    private Tcp tcp = new CollectorProperties.Tcp();
    private Kafka kafka = new CollectorProperties.Kafka();

    @Getter
    @Setter
    public static class Tcp {
        private int port = 9200;
        private int bossThreads = 1;
        private int workerThreads = 0; // 0 means default (CPU cores * 2)
    }

    @Getter
    @Setter
    public static class Kafka {
        private String topic = "trace-events";
    }
}
