package org.example.sample;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TestController {

    private final WebClient webClient;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @GetMapping("/mvc/test")
    public Object test() {
        kafkaTemplate.send("test-topic", "Hello from TestController!");
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("http")
                        .host("localhost")
                        .port(8002)
                        .path("/flux/test")
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .toFuture();
    }
}
