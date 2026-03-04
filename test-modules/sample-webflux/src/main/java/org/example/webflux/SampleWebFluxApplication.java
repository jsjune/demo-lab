package org.example.webflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@SpringBootApplication
@RestController
public class SampleWebFluxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleWebFluxApplication.class, args);
    }

    @GetMapping("/flux/test")
    public Mono<?> test() {
        return Mono.just(Map.of("message", "WebFlux is working!"));
    }
}
