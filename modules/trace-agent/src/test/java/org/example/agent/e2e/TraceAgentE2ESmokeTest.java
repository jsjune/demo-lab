package org.example.agent.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceAgentE2ESmokeTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ENABLE_FLAG = "trace.e2e.enabled";

    private FakeCollector collector;
    private Process appProcess;
    private int appPort;
    private HttpClient httpClient;

    @BeforeAll
    void beforeAll() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(ENABLE_FLAG),
            "Set -D" + ENABLE_FLAG + "=true to run javaagent e2e smoke tests.");

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        collector = new FakeCollector();
        collector.start();

        appPort = pickFreePort();
        appProcess = startE2eServerProcess(appPort, collector.port());
        waitUntilReady(appPort);
        collector.clear();
    }

    @AfterAll
    void afterAll() {
        if (appProcess != null) appProcess.destroyForcibly();
        if (collector != null) collector.close();
    }

    @BeforeEach
    void clearEvents() {
        collector.clear();
    }

    @Test
    void httpIn_startEnd_shouldBeEmittedWithHttpCategory() throws Exception {
        HttpResponse<String> response = get("/e2e/ping", null);
        assertEquals(200, response.statusCode());

        JsonNode inStart = collector.awaitEvent(e ->
            "HTTP_IN_START".equals(text(e, "type")) &&
                "HTTP".equals(text(e, "category")) &&
                "GET /e2e/ping".equals(text(e, "target")), 5);

        JsonNode inEnd = collector.awaitEvent(e ->
            "HTTP_IN_END".equals(text(e, "type")) &&
                "HTTP".equals(text(e, "category")) &&
                "GET /e2e/ping".equals(text(e, "target")) &&
                text(e, "txId").equals(text(inStart, "txId")), 5);

        assertEquals(text(inStart, "txId"), text(inEnd, "txId"));
    }

    @Test
    void async_startEnd_shouldBeEmittedWithAsyncCategory() throws Exception {
        HttpResponse<String> response = get("/e2e/async?label=smoke", null);
        assertEquals(200, response.statusCode());

        JsonNode asyncStart = collector.awaitEvent(e ->
            "ASYNC_START".equals(text(e, "type")) &&
                "ASYNC".equals(text(e, "category")), 5);

        JsonNode asyncEnd = collector.awaitEvent(e ->
            "ASYNC_END".equals(text(e, "type")) &&
                "ASYNC".equals(text(e, "category")) &&
                text(e, "txId").equals(text(asyncStart, "txId")), 5);

        assertEquals(text(asyncStart, "txId"), text(asyncEnd, "txId"));
    }

    @Test
    void outbound_clients_shouldEmitHttpOut_andPropagateTxId() throws Exception {
        verifyOutboundAndPropagation("/e2e/outbound", "GET /e2e/outbound");
        verifyOutboundAndPropagation("/e2e/outbound/rest-client", "GET /e2e/outbound/rest-client");
        verifyOutboundAndPropagation("/e2e/outbound/web-client", "GET /e2e/outbound/web-client");
    }

    @Test
    void incoming_txId_header_shouldBeAdopted() throws Exception {
        String incomingTxId = "tx-e2e-adopt-001";
        HttpResponse<String> response = get("/e2e/ping", incomingTxId);
        assertEquals(200, response.statusCode());

        JsonNode inStart = collector.awaitEvent(e ->
            "HTTP_IN_START".equals(text(e, "type")) &&
                "GET /e2e/ping".equals(text(e, "target")) &&
                incomingTxId.equals(text(e, "txId")), 5);

        assertEquals(incomingTxId, text(inStart, "txId"));
    }

    private void verifyOutboundAndPropagation(String path, String rootTarget) throws Exception {
        collector.clear();
        HttpResponse<String> response = get(path, null);
        assertEquals(200, response.statusCode(), path + " response=" + response.body());

        JsonNode rootStart = collector.awaitEvent(e ->
            "HTTP_IN_START".equals(text(e, "type")) &&
                "HTTP".equals(text(e, "category")) &&
                rootTarget.equals(text(e, "target")), 5);
        String txId = text(rootStart, "txId");
        assertFalse(txId.isBlank());

        JsonNode httpOut = collector.awaitEvent(e ->
            "HTTP_OUT".equals(text(e, "type")) &&
                "HTTP".equals(text(e, "category")) &&
                txId.equals(text(e, "txId")), 5);
        assertEquals(txId, text(httpOut, "txId"));

        JsonNode downstreamIn = collector.awaitEvent(e ->
            "HTTP_IN_START".equals(text(e, "type")) &&
                "HTTP".equals(text(e, "category")) &&
                "GET /e2e/ping".equals(text(e, "target")) &&
                txId.equals(text(e, "txId")), 5);
        assertEquals(txId, text(downstreamIn, "txId"));
    }

    private HttpResponse<String> get(String path, String txIdHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
            .timeout(Duration.ofSeconds(5))
            .GET();
        if (txIdHeader != null && !txIdHeader.isBlank()) {
            b.header("X-Tx-Id", txIdHeader);
        }
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Process startE2eServerProcess(int port, int collectorPort) throws Exception {
        Path agentJar = findSingleFile(
            Path.of("modules/trace-agent/build/libs"),
            "trace-agent-*.jar",
            name -> !name.contains("-plain"));
        Path appJar = findSingleFile(
            Path.of("modules/trace-e2e-server/build/libs"),
            "trace-e2e-server-*.jar",
            name -> !name.contains("-plain"));

        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-javaagent:" + agentJar.toAbsolutePath(),
            "-Dtrace.agent.server-name=trace-e2e-server",
            "-Dtrace.agent.collector.host=127.0.0.1",
            "-Dtrace.agent.collector.port=" + collectorPort,
            "-Dtrace.agent.sampling.rate=1.0",
            "-Dserver.port=" + port,
            "-jar", appJar.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        drainProcessOutput(p.getInputStream());
        return p;
    }

    private void waitUntilReady(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/e2e/ping"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return;
            } catch (Exception ignored) {
            }
            pauseMillis(200);
        }
        fail("trace-e2e-server did not become ready within timeout");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? "" : v.asText("");
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            return ss.getLocalPort();
        }
    }

    private static Path findSingleFile(Path dir, String glob, Predicate<String> extraFilter) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Directory not found: " + dir
                + ". Build required artifacts first.");
        }
        List<Path> matches = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches(globToRegex(glob)))
                .filter(p -> extraFilter.test(p.getFileName().toString()))
                .forEach(matches::add);
        }
        Optional<Path> newest = matches.stream()
            .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        if (newest.isEmpty()) {
            throw new IllegalStateException("No artifact matching " + glob + " in " + dir
                + ". Build :modules:trace-agent:shadowJar and :modules:trace-e2e-server:bootJar first.");
        }
        return newest.get();
    }

    private static String globToRegex(String glob) {
        return glob.replace(".", "\\.").replace("*", ".*");
    }

    private static void drainProcessOutput(InputStream in) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                while (br.readLine() != null) {
                    // intentionally drain only
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.setName("e2e-server-log-drain");
        t.start();
    }

    private static final class FakeCollector implements AutoCloseable {
        private final ServerSocket server;
        private final List<JsonNode> events = new CopyOnWriteArrayList<>();
        private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
        private volatile boolean running = true;

        private FakeCollector() throws IOException {
            this.server = new ServerSocket();
            this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        }

        int port() {
            return server.getLocalPort();
        }

        void start() {
            acceptor.submit(() -> {
                while (running) {
                    try {
                        Socket socket = server.accept();
                        handle(socket);
                    } catch (IOException ignored) {
                    }
                }
            });
        }

        private void handle(Socket socket) {
            Thread t = new Thread(() -> {
                try (Socket s = socket;
                     BufferedReader br = new BufferedReader(
                         new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        try {
                            events.add(OBJECT_MAPPER.readTree(line));
                        } catch (Exception ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            t.setDaemon(true);
            t.setName("fake-collector-conn");
            t.start();
        }

        JsonNode awaitEvent(Predicate<JsonNode> predicate, int timeoutSeconds) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            int cursor = 0;
            while (System.currentTimeMillis() < deadline) {
                List<JsonNode> snapshot = List.copyOf(events);
                while (cursor < snapshot.size()) {
                    JsonNode e = snapshot.get(cursor++);
                    if (predicate.test(e)) return e;
                }
                pauseMillis(50);
            }
            fail("Expected event not found in " + timeoutSeconds + "s. Collected events=" + events.size());
            return null;
        }

        void clear() {
            events.clear();
        }

        @Override
        public void close() {
            running = false;
            try {
                server.close();
            } catch (IOException ignored) {
            }
            acceptor.shutdownNow();
            try {
                acceptor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void pauseMillis(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
