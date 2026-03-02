package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: TcpSender (비동기 이벤트 전송)")
class TcpSenderTest {

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // queue는 init() 호출 시 생성되므로 테스트 전에 반드시 초기화
        AgentConfig.init();
        TcpSender.init();
        stopSenderDaemon();

        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        queueField.set(null, new LinkedBlockingQueue<>(AgentConfig.getBufferCapacity()));
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        // 테스트 간 큐 상태 격리
        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        BlockingQueue<TraceEvent> queue = (BlockingQueue<TraceEvent>) queueField.get(null);
        if (queue != null) queue.clear();
    }

    private void stopSenderDaemon() throws InterruptedException {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("trace-agent-sender".equals(thread.getName()) && thread.isAlive()) {
                thread.interrupt();
                thread.join(200);
            }
        }
    }

    @Test
    @DisplayName("이벤트 전송 시 내부 큐에 정상적으로 담겨야 한다")
    @SuppressWarnings("unchecked")
    void testSendEnqueuing() throws Exception {
        TraceEvent event = createDummyEvent("tx-1");

        TcpSender.send(event);

        // 리플렉션으로 내부 큐 접근
        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        BlockingQueue<TraceEvent> queue = (BlockingQueue<TraceEvent>) queueField.get(null);

        assertTrue(queue.contains(event), "큐에 이벤트가 포함되어 있어야 함");
    }

    @Test
    @DisplayName("큐 오버플로우 시 새 이벤트가 수용되고 큐 용량 불변식이 유지되어야 한다")
    @SuppressWarnings("unchecked")
    void testQueueOverflowBehavior() throws Exception {
        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);

        // 격리된 소용량 큐로 교체 — 백그라운드 daemon 스레드의 소비 영향을 최소화
        int capacity = 10;
        LinkedBlockingQueue<TraceEvent> isolatedQueue = new LinkedBlockingQueue<>(capacity);
        BlockingQueue<TraceEvent> originalQueue = (BlockingQueue<TraceEvent>) queueField.get(null);
        queueField.set(null, isolatedQueue);

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
            queueField.set(null, originalQueue); // 복원
        }
    }

    @Test
    @DisplayName("큐가 null인 경우(init 전) send() 호출 시 예외 없이 이벤트를 버려야 한다")
    @SuppressWarnings("unchecked")
    void testSendWhenQueueIsNull_silentlyDrops() throws Exception {
        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        BlockingQueue<TraceEvent> savedQueue = (BlockingQueue<TraceEvent>) queueField.get(null);

        // init 전 상태 시뮬레이션: queue를 일시적으로 null로 설정
        queueField.set(null, null);
        try {
            assertDoesNotThrow(() -> TcpSender.send(createDummyEvent("tx-preInit")),
                "queue == null 시 send()는 예외 없이 이벤트를 버려야 한다");
        } finally {
            queueField.set(null, savedQueue); // 복원
        }
    }

    private TraceEvent createDummyEvent(String txId) {
        return new TraceEvent(
            "id", txId, "s-1", TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "server", "target", 0L, true, System.currentTimeMillis(), Map.of()
        );
    }
}
