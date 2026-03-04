package org.example.e2e;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/e2e")
public class E2eController {
    private final RestTemplate restTemplate;
    private final RestClient restClient;
    private final WebClient webClient;
    private final E2eAsyncService asyncService;

    @Value("${server.port:18080}")
    private int serverPort;

    public E2eController(
        RestTemplate restTemplate,
        RestClient restClient,
        WebClient webClient,
        E2eAsyncService asyncService
    ) {
        this.restTemplate = restTemplate;
        this.restClient = restClient;
        this.webClient = webClient;
        this.asyncService = asyncService;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "trace-e2e-server");
        out.put("status", "ok");
        out.put("thread", Thread.currentThread().getName());
        return out;
    }

    @GetMapping("/outbound")
    public Map<String, Object> outbound(
        @RequestParam(value = "url", required = false) String url
    ) {
        String target = (url == null || url.isBlank())
            ? "http://127.0.0.1:" + serverPort + "/e2e/ping"
            : url;

        Object body = restTemplate.getForObject(target, Object.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("called", target);
        out.put("downstream", body);
        out.put("client", "RestTemplate");
        return out;
    }

    @GetMapping("/outbound/rest-client")
    public Map<String, Object> outboundRestClient(
        @RequestParam(value = "url", required = false) String url
    ) {
        String target = (url == null || url.isBlank())
            ? "http://127.0.0.1:" + serverPort + "/e2e/ping"
            : url;

        Object body = restClient.get()
            .uri(target)
            .retrieve()
            .body(Object.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("called", target);
        out.put("downstream", body);
        out.put("client", "RestClient");
        return out;
    }

    @GetMapping("/outbound/web-client")
    public Map<String, Object> outboundWebClient(
        @RequestParam(value = "url", required = false) String url
    ) {
        String target = (url == null || url.isBlank())
            ? "http://127.0.0.1:" + serverPort + "/e2e/ping"
            : url;

        Object body = webClient.get()
            .uri(target)
            .retrieve()
            .bodyToMono(Object.class)
            .block();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("called", target);
        out.put("downstream", body);
        out.put("client", "WebClient");
        return out;
    }

    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async(
        @RequestParam(value = "label", defaultValue = "async-e2e") String label
    ) {
        return asyncService.runAsync(label).thenApply(async -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requestThread", Thread.currentThread().getName());
            out.put("asyncResult", async);
            return out;
        });
    }

    @GetMapping("/error")
    public Map<String, Object> error() {
        throw new IllegalStateException("e2e-intentional-error");
    }
}
