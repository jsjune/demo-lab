package org.example.sample;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AsyncService asyncService;
    private final WebClient webClient;

    // -----------------------------------------------------------------------
    // Basic CRUD
    // -----------------------------------------------------------------------

    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> testAsync(@RequestParam(defaultValue = "test-async") String label) throws IllegalAccessException {
        log.info("[SAMPLE APP] Triggering async tasks: {}", label);
        asyncService.runAsyncTask(label);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("http")
                        .host("localhost")
                        .port(8002)
                        .path("/api/flux/test")
                        .queryParam("label", label)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .doOnSubscribe(s -> log.info("[COMPLEX-ASYNC] Calling downstream via WebClient"))
                .doOnSuccess(res -> log.info("[COMPLEX-ASYNC] Task completed. Downstream response size: {}", res == null ? 0 : res.size()))
                .toFuture();
    }

    @GetMapping
    public List<User> getAll() {
        log.info("[SAMPLE APP] Getting all users");
        return userRepository.findAll();
    }

    @PostMapping
    public User create(@RequestBody User user) {
        log.info("[SAMPLE APP] Creating user: {}", user.getName());
        User saved = userRepository.save(user);
        kafkaTemplate.send("sample-topic", "user-created:" + saved.getId());
        return saved;
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getById(@PathVariable Long id) {
        log.info("[SAMPLE APP] Getting user by id: {}", id);
        return userRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> update(@PathVariable Long id, @RequestBody User body) {
        log.info("[SAMPLE APP] Updating user: {}", id);
        return userRepository.findById(id).map(user -> {
            user.setName(body.getName());
            user.setEmail(body.getEmail());
            User saved = userRepository.save(user);
            redisTemplate.delete("user:" + id);   // cache invalidate
            kafkaTemplate.send("sample-topic", "user-updated:" + id);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("[SAMPLE APP] Deleting user: {}", id);
        if (!userRepository.existsById(id)) return ResponseEntity.notFound().build();
        userRepository.deleteById(id);
        redisTemplate.delete("user:" + id);
        kafkaTemplate.send("sample-topic", "user-deleted:" + id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Cache (Redis)
    // -----------------------------------------------------------------------

    @GetMapping("/cache/{id}")
    public User getFromCache(@PathVariable Long id) {
        String key = "user:" + id;
        User user = (User) redisTemplate.opsForValue().get(key);
        if (user == null) {
            log.info("[SAMPLE APP] Cache MISS for id: {}", id);
            user = userRepository.findById(id).orElse(null);
            if (user != null) {
                redisTemplate.opsForValue().set(key, user);
            }
        } else {
            log.info("[SAMPLE APP] Cache HIT for id: {}", id);
        }
        return user;
    }

    // -----------------------------------------------------------------------
    // Kafka
    // -----------------------------------------------------------------------

    @PostMapping("/message")
    public String sendMessage(@RequestParam String msg) {
        log.info("[SAMPLE APP] Sending Kafka message: {}", msg);
        kafkaTemplate.send("sample-topic", msg);
        return "Sent: " + msg;
    }

    // -----------------------------------------------------------------------
    // HTTP out (RestTemplate)
    // -----------------------------------------------------------------------

    @GetMapping("/external")
    public String callExternal() {
        log.info("[SAMPLE APP] Calling external (self)");
        return restTemplate.getForObject("http://localhost:8000/users", String.class);
    }

    /**
     * Cross-service test: MVC (sample-crud-app) ??WebFlux (sample-webflux)
     * Verifies that the agent propagates TxId via HTTP headers.
     */
    @GetMapping("/call-flux")
    public String callFlux() {
        log.info("[SAMPLE APP] Calling sample-webflux at port 8002");
        return restTemplate.getForObject("http://localhost:8002/api/flux/test?label=from-mvc", String.class);
    }

    // -----------------------------------------------------------------------
    // Trace scenario samples
    // -----------------------------------------------------------------------

    /**
     * Full chain: HTTP_IN → DB × 2 → Redis × N → Kafka → HTTP_OUT
     * Demonstrates multi-span distributed trace in one request.
     */
    @GetMapping("/chain")
    public Map<String, Object> chain() {
        log.info("[SAMPLE APP] Running full chain trace");
        List<User> users = userRepository.findAll();
        users.forEach(u -> redisTemplate.opsForValue().set("user:" + u.getId(), u));
        kafkaTemplate.send("sample-topic", "chain-sync:" + users.size());
        String downstream = restTemplate.getForObject("http://localhost:8000/users", String.class);
        return Map.of("synced", users.size(), "downstream", downstream == null ? "" : downstream);
    }

    /**
     * Bulk insert: exercises many consecutive DB_QUERY spans in a single trace.
     * POST /users/bulk?count=10
     */
    @PostMapping("/bulk")
    public Map<String, Object> bulk(@RequestParam(defaultValue = "10") int count) {
        log.info("[SAMPLE APP] Bulk inserting {} users", count);
        for (int i = 1; i <= count; i++) {
            userRepository.save(new User("bulk-user-" + i, "bulk" + i + "@test.com"));
        }
        long total = userRepository.count();
        kafkaTemplate.send("sample-topic", "bulk-inserted:" + count);
        return Map.of("inserted", count, "total", total);
    }

    /**
     * Error simulation: returns the requested HTTP status code.
     * GET /users/error?code=404  → 404 Not Found
     * GET /users/error?code=500  → 500 Internal Server Error
     * Used to verify HTTP_OUT ERR event generation in the agent.
     */
    @GetMapping("/error")
    public ResponseEntity<Map<String, Object>> simulateError(
            @RequestParam(defaultValue = "500") int code) throws Exception {
        log.info("[SAMPLE APP] Simulating HTTP error: {}", code);
//        return ResponseEntity.status(code).body(Map.of("error", "Simulated error", "code", code));
        throw new Exception("Simulated error with code " + code);
    }

    /**
     * HTTP out error: makes RestTemplate call an endpoint that returns 404.
     * Verifies that HTTP_OUT ERR trace event includes errorType + errorMessage.
     */
    @GetMapping("/trigger-http-error")
    public Map<String, Object> triggerHttpError() {
        log.info("[SAMPLE APP] Triggering outbound HTTP error");
        try {
            restTemplate.getForObject("http://localhost:8000/users/9999999", String.class);
            return Map.of("result", "no-error");
        } catch (Exception e) {
            return Map.of("caught", e.getClass().getSimpleName(), "message", e.getMessage());
        }
    }
}
