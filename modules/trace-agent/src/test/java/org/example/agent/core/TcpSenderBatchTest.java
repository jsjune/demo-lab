package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.example.agent.testutil.TestStateGuard;
import org.example.agent.testutil.TcpSenderTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("코어: TcpSender 배치 모드 (collectBatch / flushBatch)")
class TcpSenderBatchTest {
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

    // -----------------------------------------------------------------------
    // collectBatch() — 배치 크기 트리거
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("queue에 batch.size만큼 이벤트가 있으면 즉시 전체를 수집해야 한다")
    void collectBatch_batchSizeReached_returnsFullBatch() throws Exception {
        int batchSize = 5;
        for (int i = 0; i < batchSize; i++) {
            enqueue(createDummyEvent("tx-" + i));
        }

        List<TraceEvent> result = TcpSender.collectBatch(batchSize, 5000);

        assertEquals(batchSize, result.size(), "batch.size 도달 시 전체 이벤트를 반환해야 함");
    }

    // -----------------------------------------------------------------------
    // collectBatch() — timeout 트리거
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("batch.size에 미달해도 flush-ms timeout 경과 후 수집된 이벤트를 반환해야 한다")
    void collectBatch_timeoutExpires_returnsPartialBatch() throws Exception {
        int batchSize = 5;
        int enqueued = 2;
        for (int i = 0; i < enqueued; i++) {
            enqueue(createDummyEvent("tx-" + i));
        }

        List<TraceEvent> result = assertTimeout(
            Duration.ofMillis(500),
            () -> TcpSender.collectBatch(batchSize, 150),
            "collectBatch는 flush timeout 내에 반환되어야 함");

        assertEquals(enqueued, result.size(), "timeout 경과 후 수집된 이벤트 수만 반환해야 함");
    }

    // -----------------------------------------------------------------------
    // flushBatch() — 정상 flush
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("flushBatch()는 각 이벤트를 \\n으로 구분된 JSON 행으로 출력해야 한다")
    void flushBatch_writesNdjsonLines() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, false);

        List<TraceEvent> batch = new ArrayList<>();
        batch.add(createDummyEvent("tx-1"));
        batch.add(createDummyEvent("tx-2"));
        batch.add(createDummyEvent("tx-3"));

        TcpSender.flushBatch(batch, pw);

        String output = sw.toString();
        String[] lines = output.split("\n", -1);
        // split("\n", -1) → 마지막 빈 토큰 포함: "a\nb\nc\n" → ["a","b","c",""]
        int nonEmptyLines = 0;
        for (String line : lines) {
            if (!line.isEmpty()) nonEmptyLines++;
        }

        assertEquals(3, nonEmptyLines, "3개 이벤트 → 3개의 비어있지 않은 JSON 행이어야 함");
        assertTrue(output.endsWith("\n"), "마지막 행도 \\n으로 끝나야 함");
    }

    // -----------------------------------------------------------------------
    // flushBatch() — writer 오류 시 예외 발생
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("writer.checkError()가 true인 경우 flushBatch()는 예외를 발생시켜야 한다")
    void flushBatch_writerError_throwsException() throws Exception {
        // PipedInputStream을 닫으면 PipedOutputStream 쓰기 시 Broken pipe → checkError()=true
        PipedInputStream pis = new PipedInputStream();
        PipedOutputStream pos = new PipedOutputStream(pis);
        pis.close(); // 수신 측 종료
        PrintWriter brokenWriter = new PrintWriter(pos, false);

        List<TraceEvent> batch = List.of(createDummyEvent("tx-err"));

        assertThrows(Exception.class,
            () -> TcpSender.flushBatch(batch, brokenWriter),
            "writer 오류 시 flushBatch()는 예외를 발생시켜야 한다");
    }

    // -----------------------------------------------------------------------
    // 스레드 이름 검증 — sender.mode=single
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("sender.mode=single이면 'trace-agent-sender' 스레드가 기동되어야 한다")
    void senderMode_single_startsTraceAgentSenderThread() throws Exception {
        setSenderMode("single");
        TcpSender.init();

        assertTrue(TcpSenderTestSupport.waitForThreadAlive("trace-agent-sender", 2000),
            "'trace-agent-sender' 스레드가 timeout 내 실행 중이어야 함");
    }

    // -----------------------------------------------------------------------
    // 스레드 이름 검증 — sender.mode=batch
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("sender.mode=batch이면 'trace-agent-batch-sender' 스레드가 기동되어야 한다")
    void senderMode_batch_startsBatchSenderThread() throws Exception {
        setSenderMode("batch");
        TcpSender.init();

        assertTrue(TcpSenderTestSupport.waitForThreadAlive("trace-agent-batch-sender", 2000),
            "'trace-agent-batch-sender' 스레드가 timeout 내 실행 중이어야 함");
    }

    // -----------------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void enqueue(TraceEvent event) throws Exception {
        TcpSenderTestSupport.getQueue().put(event);
    }

    private void setSenderMode(String mode) throws Exception {
        Field propsField = AgentConfig.class.getDeclaredField("props");
        propsField.setAccessible(true);
        Properties props = (Properties) propsField.get(null);
        props.setProperty("sender.mode", mode);
    }

    private TraceEvent createDummyEvent(String txId) {
        return new TraceEvent(
            "id", txId, "s-1", null, TraceEventType.HTTP_IN_START, TraceCategory.HTTP,
            "server", "target", 0L, true, System.currentTimeMillis(), Map.of()
        );
    }
}
