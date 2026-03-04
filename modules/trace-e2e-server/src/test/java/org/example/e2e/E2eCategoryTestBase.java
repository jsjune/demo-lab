package org.example.e2e;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class E2eCategoryTestBase {

    private static MockTcpCollector mockCollector;

    @Autowired
    protected TestRestTemplate restTemplate;

    @BeforeAll
    static void setupCollector() throws Exception {
        if (mockCollector == null) {
            mockCollector = new MockTcpCollector();
            Thread.sleep(1000);
        }
    }

    @AfterAll
    static void teardownCollector() throws Exception {
        // Keep collector alive across category test classes.
        // Reconnect between class boundaries can drop events in short-lived e2e tests.
    }

    @BeforeEach
    void clearCollectorState() {
        mockCollector.clearAll();
    }

    protected ResponseEntity<String> callGet(String path, String txId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tx-Id", txId);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(path, HttpMethod.GET, entity, String.class);
    }

    protected List<TraceEvent> await(String txId, Predicate<List<TraceEvent>> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        List<TraceEvent> events = List.of();
        while (System.currentTimeMillis() < deadline) {
            events = mockCollector.getEventsByTxId(txId);
            if (done.test(events)) {
                return events;
            }
            Thread.sleep(100);
        }
        return events;
    }

    protected boolean hasAllTypes(List<TraceEvent> events, Set<TraceEventType> requiredTypes) {
        Set<TraceEventType> seen = events.stream().map(TraceEvent::type).collect(Collectors.toSet());
        return seen.containsAll(requiredTypes);
    }

    protected Map<TraceEventType, List<TraceEvent>> byType(List<TraceEvent> events) {
        return events.stream().collect(Collectors.groupingBy(TraceEvent::type));
    }

    protected void assertTypeCategoryAndTx(List<TraceEvent> events, TraceCategory expectedCategory, String txId) {
        assertFalse(events == null || events.isEmpty());
        assertTrue(events.stream().allMatch(e -> expectedCategory.equals(e.category())));
        assertTrue(events.stream().allMatch(e -> txId.equals(e.txId())));
    }

    protected String newTx(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
