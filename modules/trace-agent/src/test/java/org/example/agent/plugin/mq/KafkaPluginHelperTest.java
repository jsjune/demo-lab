package org.example.agent.plugin.mq;

import org.example.agent.config.AgentConfig;
import org.example.agent.core.TxIdHolder;
import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
class KafkaPluginHelperTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
        stateGuard.setPropertiesFieldValue(AgentConfig.class, "props", "header-key", "X-Tx-Id");
        TxIdHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TxIdHolder.clear();
        stateGuard.close();
    }

    @Test
    void setTxIdIfPresent_ignoresNullOrBlank() {
        KafkaPlugin.setTxIdIfPresent(null);
        assertNull(TxIdHolder.get());

        KafkaPlugin.setTxIdIfPresent("");
        assertNull(TxIdHolder.get());
    }

    @Test
    void setTxIdIfPresent_setsWhenValueExists() {
        KafkaPlugin.setTxIdIfPresent("tx-1");
        assertEquals("tx-1", TxIdHolder.get());
    }

    @Test
    void injectHeader_addsHeaderWhenRecordHasHeaders() {
        FakeProducerRecord record = new FakeProducerRecord();
        KafkaPlugin.injectHeader(record, "tx-22");
        assertArrayEquals("tx-22".getBytes(StandardCharsets.UTF_8), record.headers.last("X-Tx-Id"));
    }

    @Test
    void extractTxId_readsHeaderFromRecord() {
        FakeConsumerRecord record = new FakeConsumerRecord("topic-a");
        record.headers.add("X-Tx-Id", "tx-read-1".getBytes(StandardCharsets.UTF_8));
        assertEquals("tx-read-1", KafkaPlugin.extractTxId(record));
    }

    @Test
    void extractTopic_readsTopicViaReflection() {
        FakeConsumerRecord record = new FakeConsumerRecord("topic-b");
        assertEquals("topic-b", KafkaPlugin.extractTopic(record));
    }

    static final class FakeProducerRecord {
        final FakeHeaders headers = new FakeHeaders();
        public FakeHeaders headers() { return headers; }
    }

    static final class FakeConsumerRecord {
        final FakeHeaders headers = new FakeHeaders();
        final String topic;
        FakeConsumerRecord(String topic) { this.topic = topic; }
        public FakeHeaders headers() { return headers; }
        public String topic() { return topic; }
    }

    static final class FakeHeaders {
        private final Map<String, byte[]> store = new HashMap<>();
        public void add(String key, byte[] value) { store.put(key, value); }
        public FakeHeader lastHeader(String key) {
            byte[] val = store.get(key);
            return val == null ? null : new FakeHeader(val);
        }
        public byte[] last(String key) { return store.get(key); }
    }

    static final class FakeHeader {
        private final byte[] v;
        FakeHeader(byte[] v) { this.v = v; }
        public byte[] value() { return v; }
    }
}
