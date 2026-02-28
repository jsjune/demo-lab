package org.example.sample;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final UserRepository userRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @KafkaListener(topics = "sample-topic", groupId = "sample-group")
    public void consume(String message) {
        log.info("[SAMPLE APP] Kafka Consumer received: {}", message);

        // Update Redis event counter — CACHE_SET span in consumer trace
        redisTemplate.opsForValue().increment("kafka:event-count");

        // For chain-sync events: refresh DB records in cache — DB_QUERY + CACHE_SET spans
        if (message.startsWith("chain-sync:")) {
            userRepository.findAll()
                .forEach(u -> redisTemplate.opsForValue().set("user:" + u.getId(), u));
            log.info("[SAMPLE APP] Chain-sync cache refresh done");
        }

        // For user-deleted events: ensure cache is cleared — CACHE_DEL span
        if (message.startsWith("user-deleted:")) {
            String id = message.substring("user-deleted:".length());
            redisTemplate.delete("user:" + id);
        }
    }
}
