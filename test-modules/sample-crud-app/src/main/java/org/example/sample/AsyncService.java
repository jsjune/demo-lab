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
    public void runAsyncTask(String label) {
        log.info("[ASYNC-TEST] Executing async task for label: {}", label);
        List<User> all = userRepository.findAll();
        log.info("[ASYNC-TEST] Found {} users in background thread", all.size());
    }

    /**
     * supplyAsync + WebClient: Returns Map<String, Object>
     */
    @Async
    public CompletableFuture<Map<String, Object>> runComplexAsync(String label) {
        log.info("[COMPLEX-ASYNC] Start complex task: {}", label);

        return CompletableFuture.supplyAsync(() -> {
            log.info("[COMPLEX-ASYNC] Inside supplyAsync, calling WebClient");
            
            // Fetch as Map from downstream WebFlux service
            return webClient.get()
                .uri("http://localhost:8002/api/flux/test?label=" + label)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .toFuture()
                .join();
        }).thenApply(res -> {
            log.info("[COMPLEX-ASYNC] Task completed. Downstream response size: {}", res.size());
            return res;
        });
    }
}
