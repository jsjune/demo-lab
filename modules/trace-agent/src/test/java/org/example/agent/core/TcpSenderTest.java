package org.example.agent.core;

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

    private TraceEvent createDummyEvent(String txId) {
        return new TraceEvent(
            "id", txId, "s-1", null, TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "server", "target", 0L, true, System.currentTimeMillis(), Map.of()
        );
    }
}
