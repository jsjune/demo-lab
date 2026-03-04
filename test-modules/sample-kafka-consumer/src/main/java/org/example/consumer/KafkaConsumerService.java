package org.example.consumer;

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

    @KafkaListener(topics = "sample-topic", groupId = "sample-consumer-group")
    public void consume(String message) {
        log.info("[KAFKA CONSUMER] Received: {}", message);

        // Update Redis event counter
        redisTemplate.opsForValue().increment("kafka:event-count");

        // For chain-sync events: refresh DB records in cache
        if (message.startsWith("chain-sync:")) {
            userRepository.findAll()
                    .forEach(u -> redisTemplate.opsForValue().set("user:" + u.getId(), u));
            log.info("[KAFKA CONSUMER] Chain-sync cache refresh done");
        }

        // For user-deleted events: ensure cache is cleared
        if (message.startsWith("user-deleted:")) {
            String id = message.substring("user-deleted:".length());
            redisTemplate.delete("user:" + id);
            log.info("[KAFKA CONSUMER] Cleared cache for user: {}", id);
        }
    }

    @KafkaListener(topics = "test-topic")
    public void consumeTestTopic(String message) {
        throw new RuntimeException("[KAFKA CONSUMER] Received on test-topic: " + message);
    }
}
