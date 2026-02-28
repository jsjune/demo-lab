package org.example.agent.core;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: TcpSender (비동기 이벤트 전송)")
class TcpSenderTest {

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
        
        // 테스트 후 큐 비우기 (다른 테스트 영향 방지)
        queue.clear();
    }

    @Test
    @DisplayName("큐가 가득 찼을 때 가장 오래된 이벤트를 드랍하고 새로운 이벤트를 받아야 한다")
    @SuppressWarnings("unchecked")
    void testQueueOverflowBehavior() throws Exception {
        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        BlockingQueue<TraceEvent> queue = (BlockingQueue<TraceEvent>) queueField.get(null);
        
        queue.clear();
        
        // 큐를 최대 용량(1000)까지 채움
        for (int i = 0; i < 1000; i++) {
            TcpSender.send(createDummyEvent("old-" + i));
        }
        
        assertEquals(1000, queue.size());
        
        // 1001번째 이벤트 전송
        TraceEvent newEvent = createDummyEvent("newest-one");
        TcpSender.send(newEvent);
        
        assertEquals(1000, queue.size(), "큐 사이즈는 1000을 넘지 않아야 함");
        assertTrue(queue.contains(newEvent), "새로운 이벤트가 큐에 포함되어야 함");
        
        // 첫 번째 보냈던 이벤트가 드랍되었는지 확인
        boolean hasFirstEvent = queue.stream().anyMatch(e -> e.txId().equals("old-0"));
        assertFalse(hasFirstEvent, "가장 오래된 이벤트는 드랍되어야 함");
        
        queue.clear();
    }

    private TraceEvent createDummyEvent(String txId) {
        return new TraceEvent(
            "id", txId, "s-1", TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "server", "target", 0L, true, System.currentTimeMillis(), Map.of()
        );
    }
}
