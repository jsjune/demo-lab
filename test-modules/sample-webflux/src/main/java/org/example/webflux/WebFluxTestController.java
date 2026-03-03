package org.example.webflux;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Controller to test Phase 3: Reactor Context Propagation (WebFlux).
 * Pure WebFlux controller with no agent dependencies.
 */
@Slf4j
@RestController
@RequestMapping("/api/flux")
public class WebFluxTestController {

    /**
     * Verifies that the reactive chain works normally.
     * The agent should automatically inject and propagate TxId/SpanId in the background.
     */
    @GetMapping("/test")
    public Mono<Map<String, String>> testReactorContext(@RequestParam(defaultValue = "label") String label) {
        return Mono.deferContextual(ctx -> {
            // App is unaware of the agent's keys.
            // We just log that we are in the reactive pipeline.
            log.info("[WEBFLUX-TEST] Executing reactive pipeline for label: {}", label);

            return Mono.just(Map.of(
                "label", label,
                "status", "Pipeline executed",
                "info", "Check agent logs for TxId propagation"
            ));
        });
    }

    /**
     * Tests context propagation through a reactive chain with scheduler switching.
     */
    @GetMapping("/chain")
    public Mono<Map<String, Object>> testChain() {
        return Mono.just("start")
            .doOnNext(v -> log.info("[WEBFLUX-CHAIN] Initial step on thread: {}", Thread.currentThread().getName()))
            .map(v -> Map.of("step", "executed", "thread", Thread.currentThread().getName()));
    }
}
