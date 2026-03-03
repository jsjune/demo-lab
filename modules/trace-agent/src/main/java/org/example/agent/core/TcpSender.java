package org.example.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.config.AgentConfig;
import org.example.common.TraceEvent;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TcpSender {
    private static final long BACKOFF_MAX_MS = 30_000;

    private static volatile BlockingQueue<TraceEvent> queue;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicLong dropCounter = new AtomicLong(0);
    private static volatile boolean initialized = false;
    private static volatile boolean wasConnected = false;

    public static synchronized void init() {
        if (initialized) return;

        queue = new LinkedBlockingQueue<>(AgentConfig.getBufferCapacity());

        if ("batch".equals(AgentConfig.getSenderMode())) {
            startBatchSenderThread();
        } else {
            Thread daemon = new Thread(() -> {
                Socket socket = null;
                PrintWriter writer = null;
                long backoffMs = 1000;
                TraceEvent pendingEvent = null;

                while (true) {
                    try {
                        if (pendingEvent == null) {
                            pendingEvent = queue.poll(1, TimeUnit.SECONDS);
                        }
                        if (pendingEvent == null) continue;

                        if (socket == null || socket.isClosed() || writer == null || writer.checkError()) {
                            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                            socket = new Socket();
                            socket.connect(new InetSocketAddress(AgentConfig.getCollectorHost(), AgentConfig.getCollectorPort()), 2000);
                            writer = new PrintWriter(socket.getOutputStream(), true);
                            if (!wasConnected) {
                                AgentLogger.info("[TCP] Connected to "
                                    + AgentConfig.getCollectorHost() + ":" + AgentConfig.getCollectorPort());
                                wasConnected = true;
                            } else {
                                AgentLogger.info("[TCP] Reconnected to "
                                    + AgentConfig.getCollectorHost() + ":" + AgentConfig.getCollectorPort());
                            }
                            backoffMs = 1000;
                        }

                        writer.println(mapper.writeValueAsString(pendingEvent));
                        pendingEvent = null;

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        // pendingEvent is preserved — will be retried on next loop iteration
                        AgentLogger.warn("Collector connection failed. Retrying in " + backoffMs + "ms. Error: " + e.getMessage());
                        try { TimeUnit.MILLISECONDS.sleep(backoffMs); } catch (InterruptedException ignored) {}
                        backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
                        socket = null;
                        writer = null;
                    }
                }
            });

            daemon.setDaemon(true);
            daemon.setName("trace-agent-sender");
            daemon.start();
        }
        initialized = true;
    }

    private static void startBatchSenderThread() {
        final int  batchSize = AgentConfig.getBatchSize();
        final long flushMs   = AgentConfig.getBatchFlushMs();

        Thread daemon = new Thread(() -> {
            Socket socket = null;
            PrintWriter writer = null;
            long backoffMs = 1000;
            List<TraceEvent> pendingBatch = new ArrayList<>();

            while (true) {
                try {
                    // [1] 새 배치 수집 (pendingBatch가 빈 경우에만)
                    if (pendingBatch.isEmpty()) {
                        pendingBatch = collectBatch(batchSize, flushMs);
                    }
                    if (pendingBatch.isEmpty()) continue;

                    // [2] 연결 확인 / 재연결
                    if (socket == null || socket.isClosed() || writer == null || writer.checkError()) {
                        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                        socket = new Socket();
                        socket.connect(
                            new InetSocketAddress(
                                AgentConfig.getCollectorHost(),
                                AgentConfig.getCollectorPort()),
                            2000);
                        writer = new PrintWriter(socket.getOutputStream(), false); // autoFlush=false
                        if (!wasConnected) {
                            AgentLogger.info("[TCP-BATCH] Connected to "
                                + AgentConfig.getCollectorHost()
                                + ":" + AgentConfig.getCollectorPort());
                            wasConnected = true;
                        } else {
                            AgentLogger.info("[TCP-BATCH] Reconnected to "
                                + AgentConfig.getCollectorHost()
                                + ":" + AgentConfig.getCollectorPort());
                        }
                        backoffMs = 1000;
                    }

                    // [3] 배치 flush — 1회 TCP write
                    flushBatch(pendingBatch, writer);
                    pendingBatch.clear();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // pendingBatch 보관 — 재연결 후 재전송 (at-most-once)
                    AgentLogger.warn("[TCP-BATCH] Flush failed. Retrying in " + backoffMs
                        + "ms. Batch size: " + pendingBatch.size()
                        + ". Error: " + e.getMessage());
                    try { TimeUnit.MILLISECONDS.sleep(backoffMs); } catch (InterruptedException ignored) {}
                    backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
                    socket = null;
                    writer = null;
                }
            }
        });
        daemon.setDaemon(true);
        daemon.setName("trace-agent-batch-sender");
        daemon.start();
    }

    /** Package-private: exposed for unit testing without real TCP. */
    static List<TraceEvent> collectBatch(int batchSize, long flushMs) throws InterruptedException {
        List<TraceEvent> batch = new ArrayList<>();
        long deadline = System.currentTimeMillis() + flushMs;
        while (batch.size() < batchSize) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            TraceEvent e = queue.poll(remaining, TimeUnit.MILLISECONDS);
            if (e != null) batch.add(e);
        }
        return batch;
    }

    /** Package-private: exposed for unit testing without real TCP. */
    static void flushBatch(List<TraceEvent> batch, PrintWriter writer) throws Exception {
        for (TraceEvent e : batch) {
            writer.print(mapper.writeValueAsString(e) + "\n");
        }
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Batch flush failed (writer error)");
        }
    }

    public static void send(TraceEvent event) {
        BlockingQueue<TraceEvent> q = queue; // volatile read를 로컬 변수로 캡처
        if (q == null) return;              // init() 이전 극단적 호출 시 조용히 드롭
        // Drop oldest event to make room, then enqueue the new one
        if (!q.offer(event)) {
            TraceEvent dropped = q.poll();
            if (dropped != null) {
                long count = dropCounter.incrementAndGet();
                if (count % 100 == 1) {
                    AgentLogger.warn("Buffer full. Dropped oldest event. Total drops: " + count);
                }
            }
            q.offer(event);
        }
    }
}
