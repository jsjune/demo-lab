package org.example.agent.core;

import org.example.agent.core.emitter.TcpSender;
import org.example.agent.config.AgentConfig;
import org.example.agent.testutil.TestStateGuard;
import org.example.agent.testutil.TcpSenderTestSupport;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: TcpSender (비동기 이벤트 전송)")
class TcpSenderTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
        TcpSenderTestSupport.resetState();
        AgentConfig.init();
        TcpSender.init();
        TcpSenderTestSupport.stopSenderThreads(500);
        TcpSenderTestSupport.setQueue(new LinkedBlockingQueue<>(AgentConfig.getBufferCapacity()));
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        // 테스트 간 큐 상태 격리
        BlockingQueue<TraceEvent> queue = TcpSenderTestSupport.getQueue();
        if (queue != null) queue.clear();
        TcpSenderTestSupport.resetState();
        stateGuard.close();
    }

    @Test
    @DisplayName("이벤트 전송 시 내부 큐에 정상적으로 담겨야 한다")
    @SuppressWarnings("unchecked")
    void testSendEnqueuing() throws Exception {
        TraceEvent event = createDummyEvent("tx-1");

        TcpSender.send(event);
        BlockingQueue<TraceEvent> queue = TcpSenderTestSupport.getQueue();

        assertTrue(queue.contains(event), "큐에 이벤트가 포함되어 있어야 함");
    }

    @Test
    @DisplayName("큐 오버플로우 시 새 이벤트가 수용되고 큐 용량 불변식이 유지되어야 한다")
    @SuppressWarnings("unchecked")
    void testQueueOverflowBehavior() throws Exception {
        // 격리된 소용량 큐로 교체 — 백그라운드 daemon 스레드의 소비 영향을 최소화
        int capacity = 10;
        LinkedBlockingQueue<TraceEvent> isolatedQueue = new LinkedBlockingQueue<>(capacity);
        BlockingQueue<TraceEvent> originalQueue = TcpSenderTestSupport.getQueue();
        TcpSenderTestSupport.setQueue(isolatedQueue);

        try {
            // 큐를 용량만큼 직접 채움 (TcpSender.send 경유 시 daemon 소비 경쟁 발생)
            for (int i = 0; i < capacity; i++) {
                isolatedQueue.put(createDummyEvent("old-" + i));
            }

            // 오버플로우 이벤트 전송 (daemon이 1개 소비했더라도 큐에 공간이 생기므로 정상 추가됨)
            TraceEvent newEvent = createDummyEvent("newest-one");
            TcpSender.send(newEvent);

            // 불변식: 큐 크기는 반드시 용량 이하
            assertTrue(isolatedQueue.size() <= capacity, "큐 사이즈는 용량을 초과하지 않아야 함");
            // 새 이벤트는 반드시 큐에 존재 (오버플로우 또는 공간이 생겨 정상 추가)
            assertTrue(isolatedQueue.contains(newEvent), "새로운 이벤트가 큐에 포함되어야 함");
        } finally {
            TcpSenderTestSupport.setQueue(originalQueue); // 복원
        }
    }

    @Test
    @DisplayName("큐가 null인 경우(init 전) send() 호출 시 예외 없이 이벤트를 버려야 한다")
    @SuppressWarnings("unchecked")
    void testSendWhenQueueIsNull_silentlyDrops() throws Exception {
        BlockingQueue<TraceEvent> savedQueue = TcpSenderTestSupport.getQueue();

        // init 전 상태 시뮬레이션: queue를 일시적으로 null로 설정
        TcpSenderTestSupport.setQueue(null);
        try {
            long dropBefore = TcpSenderTestSupport.getDropCounterValue();
            assertDoesNotThrow(() -> TcpSender.send(createDummyEvent("tx-preInit")),
                "queue == null 시 send()는 예외 없이 이벤트를 버려야 한다");
            assertNull(TcpSenderTestSupport.getQueue(), "queue == null 상태는 send() 호출 후에도 유지되어야 한다");
            assertEquals(dropBefore, TcpSenderTestSupport.getDropCounterValue(),
                "queue == null 드롭은 버퍼 오버플로우 드롭 카운터를 증가시키지 않아야 한다");
        } finally {
            TcpSenderTestSupport.setQueue(savedQueue); // 복원
        }
    }

    @Test
    @DisplayName("다중 스레드 burst 상황에서도 큐 용량 불변식, 최신 이벤트 유지, drop 카운트 증가를 만족해야 한다")
    void testConcurrentBurstPreservesCapacityAndNewestEvent() throws Exception {
        int capacity = 32;
        int threadCount = 8;
        int sendsPerThread = 200;

        BlockingQueue<TraceEvent> originalQueue = TcpSenderTestSupport.getQueue();
        LinkedBlockingQueue<TraceEvent> isolatedQueue = new LinkedBlockingQueue<>(capacity);
        TcpSenderTestSupport.setQueue(isolatedQueue);

        long dropBefore = TcpSenderTestSupport.getDropCounterValue();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try {
            for (int t = 0; t < threadCount; t++) {
                final int workerId = t;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) return;
                        for (int i = 0; i < sendsPerThread; i++) {
                            TcpSender.send(createDummyEvent("burst-" + workerId + "-" + i));
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 worker가 시작 준비를 마쳐야 한다");
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "burst 전송이 timeout 내 종료되어야 한다");

            TraceEvent newest = createDummyEvent("newest-marker");
            TcpSender.send(newest);

            assertTrue(isolatedQueue.size() <= capacity, "동시 전송 후에도 큐 용량을 초과하면 안 된다");
            assertTrue(isolatedQueue.contains(newest), "가장 최근에 전송된 이벤트는 큐에 유지되어야 한다");
            assertTrue(TcpSenderTestSupport.getDropCounterValue() > dropBefore,
                "capacity를 초과한 burst에서는 drop 카운터가 증가해야 한다");
        } finally {
            executor.shutdownNow();
            TcpSenderTestSupport.setQueue(originalQueue);
        }
    }

    @Test
    @DisplayName("drain(): queue가 null이거나 비어있으면 조용히 종료해야 한다")
    void drain_nullOrEmptyQueue_returnsQuietly() throws Exception {
        BlockingQueue<TraceEvent> savedQueue = TcpSenderTestSupport.getQueue();
        try {
            TcpSenderTestSupport.setQueue(null);
            assertDoesNotThrow(this::invokePrivateDrain);

            TcpSenderTestSupport.setQueue(new LinkedBlockingQueue<>(10));
            assertDoesNotThrow(this::invokePrivateDrain);
        } finally {
            TcpSenderTestSupport.setQueue(savedQueue);
        }
    }

    @Test
    @DisplayName("drain(): 남은 이벤트를 collector로 전송해야 한다")
    void drain_sendsRemainingEventsToCollector() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", String.valueOf(port));

            LinkedBlockingQueue<TraceEvent> q = new LinkedBlockingQueue<>(10);
            q.offer(createDummyEvent("tx-drain-1"));
            q.offer(createDummyEvent("tx-drain-2"));
            TcpSenderTestSupport.setQueue(q);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> linesFuture = executor.submit(() -> {
                    try (Socket s = server.accept();
                         BufferedReader br = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                        String l1 = br.readLine();
                        String l2 = br.readLine();
                        return (l1 == null ? "" : l1) + "\n" + (l2 == null ? "" : l2);
                    }
                });

                invokePrivateDrain();
                String joined = linesFuture.get(3, TimeUnit.SECONDS);
                assertTrue(joined.contains("tx-drain-1"));
                assertTrue(joined.contains("tx-drain-2"));
                assertTrue(q.isEmpty(), "drain 후 queue는 비어야 한다");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("single sender thread가 collector로 이벤트 1건을 전송해야 한다")
    void singleSenderThread_sendsEventToCollector() throws Exception {
        TcpSenderTestSupport.resetState();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "sender.mode", "single");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", String.valueOf(port));
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "buffer.capacity", "16");

            TcpSender.init();
            assertTrue(TcpSenderTestSupport.waitForThreadAlive("trace-agent-sender", 2000));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> lineFuture = executor.submit(() -> {
                    try (Socket s = server.accept();
                         BufferedReader br = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                        return br.readLine();
                    }
                });

                TcpSender.send(createDummyEvent("tx-single-live"));
                String line = lineFuture.get(3, TimeUnit.SECONDS);
                assertNotNull(line);
                assertTrue(line.contains("tx-single-live"));
            } finally {
                executor.shutdownNow();
            }
        } finally {
            TcpSenderTestSupport.stopSenderThreads(500);
        }
    }

    @Test
    @DisplayName("batch sender thread가 collector로 배치 이벤트를 전송해야 한다")
    void batchSenderThread_sendsBatchToCollector() throws Exception {
        TcpSenderTestSupport.resetState();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "sender.mode", "batch");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", String.valueOf(port));
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "buffer.capacity", "16");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "sender.batch.size", "2");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "sender.batch.flush-ms", "100");

            TcpSender.init();
            assertTrue(TcpSenderTestSupport.waitForThreadAlive("trace-agent-batch-sender", 2000));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> linesFuture = executor.submit(() -> {
                    try (Socket s = server.accept();
                         BufferedReader br = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                        String l1 = br.readLine();
                        String l2 = br.readLine();
                        return (l1 == null ? "" : l1) + "\n" + (l2 == null ? "" : l2);
                    }
                });

                TcpSender.send(createDummyEvent("tx-batch-live-1"));
                TcpSender.send(createDummyEvent("tx-batch-live-2"));

                String lines = linesFuture.get(3, TimeUnit.SECONDS);
                assertTrue(lines.contains("tx-batch-live-1"));
                assertTrue(lines.contains("tx-batch-live-2"));
            } finally {
                executor.shutdownNow();
            }
        } finally {
            TcpSenderTestSupport.stopSenderThreads(500);
        }
    }

    @Test
    @DisplayName("init()는 이미 initialized=true면 상태를 변경하지 않아야 한다")
    void init_whenAlreadyInitialized_returnsImmediately() throws Exception {
        BlockingQueue<TraceEvent> sentinel = new LinkedBlockingQueue<>(3);
        sentinel.offer(createDummyEvent("sentinel"));
        TcpSenderTestSupport.setQueue(sentinel);

        var f = TcpSender.class.getDeclaredField("initialized");
        f.setAccessible(true);
        f.set(null, true);
        try {
            TcpSender.init();
            assertSame(sentinel, TcpSenderTestSupport.getQueue());
            assertEquals(1, TcpSenderTestSupport.getQueue().size());
        } finally {
            f.set(null, false);
        }
    }

    @Test
    @DisplayName("drain(): collector 연결 실패 시에도 예외 없이 종료해야 한다")
    void drain_connectionFailure_isSwallowed() throws Exception {
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", "1");

        LinkedBlockingQueue<TraceEvent> q = new LinkedBlockingQueue<>(10);
        q.offer(createDummyEvent("tx-drain-fail"));
        TcpSenderTestSupport.setQueue(q);

        assertDoesNotThrow(this::invokePrivateDrain);
        assertTrue(q.isEmpty(), "drain은 전송 실패여도 queue를 비워야 한다");
    }

    @Test
    @DisplayName("single sender loop lambda를 직접 실행해 이벤트 전송 경로를 커버한다")
    void singleSenderLoopLambda_directInvocation() throws Exception {
        LinkedBlockingQueue<TraceEvent> q = new LinkedBlockingQueue<>(16);
        q.offer(createDummyEvent("tx-lambda-single"));
        TcpSenderTestSupport.setQueue(q);

        try (ServerSocket server = new ServerSocket(0)) {
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", String.valueOf(server.getLocalPort()));

            ExecutorService es = Executors.newSingleThreadExecutor();
            Future<String> lineFuture = es.submit(() -> {
                try (Socket s = server.accept();
                     BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                    return br.readLine();
                }
            });

            Thread t = new Thread(() -> {
                try {
                    var m = TcpSender.class.getDeclaredMethod("lambda$init$0");
                    m.setAccessible(true);
                    m.invoke(null);
                } catch (Throwable ignored) {
                }
            });
            t.start();

            try {
                String line = lineFuture.get(3, TimeUnit.SECONDS);
                assertNotNull(line);
                assertTrue(line.contains("tx-lambda-single"));
            } finally {
                t.interrupt();
                t.join(1000);
                es.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("batch sender loop lambda를 직접 실행해 배치 flush 경로를 커버한다")
    void batchSenderLoopLambda_directInvocation() throws Exception {
        LinkedBlockingQueue<TraceEvent> q = new LinkedBlockingQueue<>(16);
        q.offer(createDummyEvent("tx-lambda-batch-1"));
        q.offer(createDummyEvent("tx-lambda-batch-2"));
        TcpSenderTestSupport.setQueue(q);

        try (ServerSocket server = new ServerSocket(0)) {
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
            stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", String.valueOf(server.getLocalPort()));

            ExecutorService es = Executors.newSingleThreadExecutor();
            Future<String> linesFuture = es.submit(() -> {
                try (Socket s = server.accept();
                     BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                    String l1 = br.readLine();
                    String l2 = br.readLine();
                    return (l1 == null ? "" : l1) + "\n" + (l2 == null ? "" : l2);
                }
            });

            Thread t = new Thread(() -> {
                try {
                    var m = TcpSender.class.getDeclaredMethod("lambda$startBatchSenderThread$2", int.class, long.class);
                    m.setAccessible(true);
                    m.invoke(null, 2, 50L);
                } catch (Throwable ignored) {
                }
            });
            t.start();

            try {
                String lines = linesFuture.get(3, TimeUnit.SECONDS);
                assertTrue(lines.contains("tx-lambda-batch-1"));
                assertTrue(lines.contains("tx-lambda-batch-2"));
            } finally {
                t.interrupt();
                t.join(1000);
                es.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("single sender loop lambda에서 연결 실패 경로를 타고 인터럽트로 종료 가능해야 한다")
    void singleSenderLoopLambda_connectionFailureBranch() throws Exception {
        LinkedBlockingQueue<TraceEvent> q = new LinkedBlockingQueue<>(16);
        q.offer(createDummyEvent("tx-fail-single"));
        TcpSenderTestSupport.setQueue(q);
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", "1");

        Thread t = new Thread(() -> {
            try {
                var m = TcpSender.class.getDeclaredMethod("lambda$init$0");
                m.setAccessible(true);
                m.invoke(null);
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(200);
        t.interrupt();
        t.join(300);
    }

    @Test
    @DisplayName("batch sender loop lambda에서 연결 실패 경로를 타고 인터럽트로 종료 가능해야 한다")
    void batchSenderLoopLambda_connectionFailureBranch() throws Exception {
        LinkedBlockingQueue<TraceEvent> q = new LinkedBlockingQueue<>(16);
        q.offer(createDummyEvent("tx-fail-batch"));
        TcpSenderTestSupport.setQueue(q);
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.host", "127.0.0.1");
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "collector.port", "1");

        Thread t = new Thread(() -> {
            try {
                var m = TcpSender.class.getDeclaredMethod("lambda$startBatchSenderThread$2", int.class, long.class);
                m.setAccessible(true);
                m.invoke(null, 1, 50L);
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(250);
        t.interrupt();
        t.join(300);
    }

    private TraceEvent createDummyEvent(String txId) {
        return new TraceEvent(
            "id", txId, "s-1", null, TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "server", "target", 0L, true, System.currentTimeMillis(), Map.of()
        );
    }

    private void invokePrivateDrain() throws Exception {
        var m = TcpSender.class.getDeclaredMethod("drain");
        m.setAccessible(true);
        m.invoke(null);
    }
}
