package org.example.e2e;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class E2eAsyncService {

    @Async("e2eExecutor")
    public CompletableFuture<Map<String, Object>> runAsync(String label) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("thread", Thread.currentThread().getName());
        out.put("status", "ok");
        return CompletableFuture.completedFuture(out);
    }

    @Async("e2eExecutor")
    public CompletableFuture<Map<String, Object>> runAsyncFail(String label) {
        throw new IllegalStateException("forced-async-failure:" + label);
    }
}
