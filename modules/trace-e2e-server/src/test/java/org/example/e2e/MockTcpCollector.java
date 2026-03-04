package org.example.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.common.TraceEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MockTcpCollector implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final BlockingQueue<TraceEvent> eventQueue = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<TraceEvent> allEvents = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Thread listenerThread;
    private volatile boolean running = true;

    public MockTcpCollector() throws Exception {
        int port = Integer.parseInt(System.getProperty("trace.agent.collector.port", "19200"));
        this.serverSocket = new ServerSocket(port);

        this.listenerThread = new Thread(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> handleConnection(socket)).start();
                } catch (Exception ignored) {
                }
            }
        });
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    private void handleConnection(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    TraceEvent event = mapper.readValue(line, TraceEvent.class);
                    eventQueue.offer(event);
                    allEvents.add(event);
                } catch (Exception e) {
                    System.err.println("Failed to parse event JSON: " + line);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public List<TraceEvent> drainEvents() {
        List<TraceEvent> drained = new ArrayList<>();
        eventQueue.drainTo(drained);
        return drained;
    }

    public void clearAll() {
        eventQueue.clear();
        allEvents.clear();
    }

    public List<TraceEvent> waitForEvents(String txId, int minCount, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            List<TraceEvent> matched = new ArrayList<>();
            for (TraceEvent event : allEvents) {
                if (txId.equals(event.txId())) {
                    matched.add(event);
                }
            }
            if (matched.size() >= minCount) {
                return matched;
            }
            Thread.sleep(100);
        }
        List<TraceEvent> matched = new ArrayList<>();
        for (TraceEvent event : allEvents) {
            if (txId.equals(event.txId())) {
                matched.add(event);
            }
        }
        return matched;
    }

    public List<TraceEvent> getEventsByTxId(String txId) {
        List<TraceEvent> matched = new ArrayList<>();
        for (TraceEvent event : allEvents) {
            if (txId.equals(event.txId())) {
                matched.add(event);
            }
        }
        return matched;
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() throws Exception {
        running = false;
        if (!serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
