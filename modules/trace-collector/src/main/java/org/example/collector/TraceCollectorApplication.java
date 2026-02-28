package org.example.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CollectorProperties.class)
public class TraceCollectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TraceCollectorApplication.class, args);
    }
}
