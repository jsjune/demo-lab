package org.example.e2e.controller;

import io.lettuce.core.FakeRedisAsyncCommands;
import org.example.e2e.E2eAsyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ScenarioService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private FakeRedisAsyncCommands fakeRedis;

    @Autowired
    private E2eAsyncService asyncService;

    private final AtomicReference<CountDownLatch> consumeLatchRef =
        new AtomicReference<>(new CountDownLatch(0));
    private final AtomicReference<CountDownLatch> consumeFailLatchRef =
        new AtomicReference<>(new CountDownLatch(0));

    public CompletableFuture<String> executeComplexFlow() {
        // DB Query (JDBC)
        jdbcTemplate.execute("SELECT 1");

        // Kafka Produce
        kafkaTemplate.send("e2e-topic", "test-key", "test-message");

        return asyncService.runAsync("complex").thenApply(v -> "complex_done");
    }

    public String executeFullFlow(String baseUrl) throws Exception {
        // HTTP_OUT: call another endpoint in same server
        restTemplate.getForEntity(baseUrl + "/api/test/ping", String.class);

        // ASYNC
        asyncService.runAsync("all").get(5, TimeUnit.SECONDS);
        // DB
        jdbcTemplate.queryForObject("SELECT ?", Integer.class, 1);

        // MQ_PRODUCE + MQ_CONSUME_START/END
        CountDownLatch latch = new CountDownLatch(1);
        consumeLatchRef.set(latch);
        kafkaTemplate.send("e2e-topic", "full-key", "full-message").get(5, TimeUnit.SECONDS);
        latch.await(5, TimeUnit.SECONDS);

        // CACHE_SET/HIT/MISS/DEL
        fakeRedis.set("cache-hit-key", "v");
        fakeRedis.get("cache-hit-key");
        fakeRedis.get("cache-miss-key");
        fakeRedis.del("cache-hit-key");

        // CACHE_ERROR
        try {
            fakeRedis.get("err:forced");
        } catch (Exception ignored) {
        }

        return "all_done";
    }

    public String runHttpSuccess(String baseUrl) {
        restTemplate.getForEntity(baseUrl + "/api/test/ping", String.class);
        return "http_ok";
    }

    public String runHttpOutFail() {
        try {
            restTemplate.getForEntity("http://127.0.0.1:1/not-open", String.class);
        } catch (Exception ignored) {
        }
        return "http_out_fail_done";
    }

    public String runMqSuccess() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        consumeLatchRef.set(latch);
        kafkaTemplate.send("e2e-topic", "mq-ok-key", "mq-ok-message").get(5, TimeUnit.SECONDS);
        latch.await(5, TimeUnit.SECONDS);
        return "mq_ok";
    }

    public String runMqFail() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        consumeFailLatchRef.set(latch);
        kafkaTemplate.send("e2e-topic-fail", "mq-fail-key", "mq-fail-message").get(5, TimeUnit.SECONDS);
        latch.await(5, TimeUnit.SECONDS);
        return "mq_fail";
    }

    public String runAsyncSuccess() throws Exception {
        asyncService.runAsync("async-ok").get(5, TimeUnit.SECONDS);
        return "async_ok";
    }

    public String runAsyncFail() {
        try {
            asyncService.runAsyncFail("async-fail").get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return "async_fail";
    }

    public String runDbSuccess() {
        jdbcTemplate.queryForObject("SELECT ?", Integer.class, 1);
        return "db_ok";
    }

    public String runDbFail() {
        try {
            // Force PreparedStatement execute-time SQL error path for DB_QUERY(fail).
            jdbcTemplate.queryForObject("SELECT CAST(? AS INT)", Integer.class, "not-a-number");
        } catch (Exception ignored) {
        }
        return "db_fail";
    }

    public String runDbFailStatementSyntax() {
        try {
            // PreparedStatement.execute* path with execute-time numeric range overflow.
            jdbcTemplate.queryForObject("SELECT CAST(? AS TINYINT)", Integer.class, 1000);
        } catch (Exception ignored) {
        }
        return "db_fail_statement_syntax";
    }

    public String runDbFailPrepareSyntax() {
        try {
            // PreparedStatement.execute* path with execute-time arithmetic failure.
            jdbcTemplate.queryForObject("SELECT ? / 0", Integer.class, 1);
        } catch (Exception ignored) {
        }
        return "db_fail_prepare_syntax";
    }

    public String runCacheSuccess() {
        fakeRedis.set("cache-only-key", "v");
        fakeRedis.get("cache-only-key");
        fakeRedis.get("cache-only-miss-key");
        fakeRedis.del("cache-only-key");
        return "cache_ok";
    }

    public String runCacheFail() {
        try {
            fakeRedis.get("err:cache-only");
        } catch (Exception ignored) {
        }
        return "cache_fail";
    }

    @KafkaListener(topics = "e2e-topic", groupId = "trace-e2e-consumer")
    public void onMessage(String payload) {
        CountDownLatch latch = consumeLatchRef.get();
        if (latch != null) {
            latch.countDown();
        }
    }

    @KafkaListener(topics = "e2e-topic-fail", groupId = "trace-e2e-consumer-fail")
    public void onMessageFail(String payload) {
        CountDownLatch latch = consumeFailLatchRef.get();
        if (latch != null) {
            latch.countDown();
        }
        throw new IllegalStateException("forced-mq-consume-failure");
    }

    public void executeErrorFlow() {
        throw new RuntimeException("E2E Test Error");
    }
}
