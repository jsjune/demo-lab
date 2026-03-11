package org.example.sample;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service to test Phase 4: ThreadPool Context Propagation.
 * Returns response as a Map via CompletableFuture.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncService {
    private final UserRepository userRepository;
    private final WebClient webClient;

    @Async
    public void runAsyncTask(String label) throws IllegalAccessException {
        log.info("[ASYNC-TEST] Executing async task for label: {}", label);
        List<User> all = userRepository.findAll();
        log.info("[ASYNC-TEST] Found {} users in background thread", all.size());
//        webClient.get()
//                .uri(uriBuilder -> uriBuilder
//                        .scheme("http")
//                        .host("localhost")
//                        .port(8002)
//                        .path("/flux/test")
//                        .build())
//                .retrieve()
//                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
//                .toFuture();
        throw new IllegalAccessException("Intentional exception from async task: " + label);
    }

}
