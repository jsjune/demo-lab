package org.example.agent.core.emitter;

import org.example.agent.config.AgentConfig;
import org.example.agent.testutil.TcpSenderTestSupport;
import org.example.agent.testutil.TestStateGuard;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: TcpSender Shutdown Hook (drain)")
class TcpSenderShutdownTest {

    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
        TcpSenderTestSupport.resetState();
        TcpSenderTestSupport.setQueue(new LinkedBlockingQueue<>(100));
    }

    @AfterEach
    void tearDown() throws Exception {
        TcpSenderTestSupport.resetState();
        stateGuard.close();
    }

    // ─────────────────────────────────────────────────────────────
    // T-01: drainTo — 빈 리스트
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("drainTo: 빈 이벤트 목록이면 writer에 아무것도 기록되지 않아야 한다")
    void drainTo_emptyList_writesNothing() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        TcpSender.drainTo(List.of(), pw);

        assertTrue(sw.toString().isEmpty(), "빈 목록이면 writer 출력이 없어야 한다");
    }

    // ─────────────────────────────────────────────────────────────
    // T-02: drainTo — 이벤트 직렬화
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("drainTo: 각 이벤트를 JSON 한 줄로 직렬화하여 writer에 기록해야 한다")
    void drainTo_serializesAllEvents() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        List<TraceEvent> events = List.of(createEvent("tx-alpha"), createEvent("tx-beta"));

        TcpSender.drainTo(events, pw);

        String output = sw.toString();
        long lineCount = Arrays.stream(output.split("\n"))
            .filter(l -> !l.isBlank())
            .count();
        assertEquals(2, lineCount, "이벤트 수만큼 줄이 출력되어야 한다");
        assertTrue(output.contains("tx-alpha"), "첫 번째 이벤트 txId가 포함되어야 한다");
        assertTrue(output.contains("tx-beta"),  "두 번째 이벤트 txId가 포함되어야 한다");
    }

    // ─────────────────────────────────────────────────────────────
    // T-03: drainTo — writer 오류 → IOException
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("drainTo: PrintWriter 오류 상태에서는 IOException을 던져야 한다")
    void drainTo_writerError_throwsIOException() {
        PrintWriter errorWriter = new PrintWriter(new StringWriter()) {
            @Override
            public boolean checkError() { return true; }
        };
        List<TraceEvent> events = List.of(createEvent("tx-err"));

        assertThrows(IOException.class, () -> TcpSender.drainTo(events, errorWriter),
            "writer 오류 시 IOException이 발생해야 한다");
    }

    // ─────────────────────────────────────────────────────────────
    // T-04: drain() — Collector 미기동 → 예외 swallow
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("drain: Collector가 없을 때도 예외가 호출자에게 전파되지 않아야 한다")
    void drain_collectorDown_swallowsException() throws Exception {
        BlockingQueue<TraceEvent> queue = TcpSenderTestSupport.getQueue();
        queue.add(createEvent("tx-drain"));

        Method drain = TcpSender.class.getDeclaredMethod("drain");
        drain.setAccessible(true);

        // InvocationTargetException이 발생하면 그 cause가 전파된다 → 설계 위반
        assertDoesNotThrow(() -> drain.invoke(null),
            "Collector 연결 실패 시에도 drain()은 예외를 전파하지 않아야 한다");
    }

    // ─────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────
    private static TraceEvent createEvent(String txId) {
        return new TraceEvent(
            "id-" + txId, txId, "span-1", null,
            TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "test-server", "/test", 0L, true,
            System.currentTimeMillis(), Map.of()
        );
    }
}
