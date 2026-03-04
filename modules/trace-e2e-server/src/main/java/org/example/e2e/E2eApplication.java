package org.example.e2e;

import io.lettuce.core.FakeRedisAsyncCommands;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

@SpringBootApplication
@EnableAsync
@EnableKafka
public class E2eApplication {
    public static void main(String[] args) {
        SpringApplication.run(E2eApplication.class, args);
    }

    @Bean(name = "e2eExecutor")
    public Executor e2eExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("e2e-async-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean
    public FakeRedisAsyncCommands fakeRedisAsyncCommands() {
        return new FakeRedisAsyncCommands();
    }
}
