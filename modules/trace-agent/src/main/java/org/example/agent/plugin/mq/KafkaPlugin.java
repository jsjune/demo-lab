package org.example.agent.plugin.mq;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.example.agent.AgentInitializer;
import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.util.AgentLogger;
import org.example.agent.core.context.SpanIdHolder;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.context.TxIdHolder;
import org.example.agent.core.util.ReflectionUtils;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * KafkaPlugin: instruments KafkaProducer.send() and @KafkaListener adapter for MQ tracing.
 *
 * <p>Uses ByteBuddy @Advice inline instrumentation — no raw ASM required.
 * Pre-scan workaround (SKIP_CODE/FRAMES/DEBUG) is no longer necessary since
 * ByteBuddy's ElementMatcher handles class filtering before bytecode processing.
 */
public class KafkaPlugin implements TracerPlugin {

    @Override public String pluginId() { return "mq"; }

    @Override
    public AgentBuilder install(AgentBuilder builder) {
        if (!isEnabled()) return builder;

        ClassFileLocator agentLocator = AgentInitializer.getAgentLocator();

        return builder
            // KafkaProducer.send(ProducerRecord, Callback) — inject txId header + record produce event
            .type(nameStartsWith("org.apache.kafka.clients.producer."))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(KafkaProducerEnterAdvice.class, agentLocator)
                    .on(named("send")
                        .and(takesArgument(0,
                            named("org.apache.kafka.clients.producer.ProducerRecord"))))))
            // MessagingMessageListenerAdapter.onMessage(ConsumerRecord, ...) — extract txId + record consume
            .type(nameStartsWith("org.springframework.kafka.listener.")
                .and(nameContains("MessagingMessageListenerAdapter")))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(KafkaAdapterAdvice.class, agentLocator)
                    .on(named("onMessage")
                        .and(takesArgument(0,
                            named("org.apache.kafka.clients.consumer.ConsumerRecord"))))))
            // AbstractMessageListenerContainer.handleException() — mark consume error
            .type(nameStartsWith("org.springframework.kafka.listener."))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(KafkaHandleExceptionEnterAdvice.class, agentLocator)
                    .on(named("handleException")
                        .and(takesArgument(0, isSubTypeOf(Throwable.class))))));
    }

    // -----------------------------------------------------------------------
    // Advice classes
    // -----------------------------------------------------------------------

    /**
     * Intercepts KafkaConsumer.poll() for pure (non-Spring) consumer loop support.
     * Emits MQ_CONSUME_START + MQ_CONSUME_END per poll batch when records are returned.
     */
    public static class KafkaPollAdvice {

        @Advice.OnMethodEnter
        static void enter(@Advice.Local("startTime") long startTime) {
            startTime = System.currentTimeMillis();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Return Object records,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("startTime") long startTime
        ) {
            if (thrown != null) return;
            if (KafkaPlugin.getRecordsCount(records) == 0) return;
            long durationMs = System.currentTimeMillis() - startTime;
            String topic = KafkaPlugin.getFirstTopic(records);
            TraceRuntime.onMqConsumeStart("kafka", topic, TxIdHolder.get());
            TraceRuntime.onMqConsumeComplete("kafka", topic, durationMs);
        }
    }

    /** Injects txId header into ProducerRecord and records produce event. */
    public static class KafkaProducerEnterAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Object record) {
            KafkaPlugin.injectHeader(record, TxIdHolder.get());
            String topic = KafkaPlugin.extractTopic(record);
            String key = KafkaPlugin.extractKey(record);
            TraceRuntime.onMqProduce("kafka", topic, key);
        }
    }

    /** Extracts txId from ConsumerRecord headers, records consume start/end/error. */
    public static class KafkaAdapterAdvice {

        @Advice.OnMethodEnter
        static void enter(
            @Advice.Argument(0) Object record,
            @Advice.Local("topic") String topic,
            @Advice.Local("startTime") long startTime
        ) {
            String txId = KafkaPlugin.extractTxId(record);
            KafkaPlugin.setTxIdIfPresent(txId);
            topic = KafkaPlugin.extractTopic(record);
            startTime = System.currentTimeMillis();
            TraceRuntime.onMqConsumeStart("kafka", topic, TxIdHolder.get());
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("topic") String topic,
            @Advice.Local("startTime") long startTime
        ) {
            long durationMs = System.currentTimeMillis() - startTime;
            if (thrown != null) {
                TraceRuntime.onMqConsumeError(thrown, "kafka", topic, durationMs);
            } else {
                TraceRuntime.onMqConsumeComplete("kafka", topic, durationMs);
            }
        }
    }

    /**
     * Intercepts handleException() to handle the case where the Kafka adapter
     * catches the user listener exception and routes it through handleException()
     * instead of propagating ATHROW from onMessage().
     */
    public static class KafkaHandleExceptionEnterAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Throwable throwable) {
            TraceRuntime.onMqConsumeErrorMark(throwable);
            TraceRuntime.onMqConsumeComplete("kafka", null, -1L);
        }
    }

    // -----------------------------------------------------------------------
    // Static helpers — called from injected bytecode or tests
    // -----------------------------------------------------------------------

    /** Returns the total number of records in a ConsumerRecords batch (0 if null/empty). */
    @SuppressWarnings("unchecked")
    public static int getRecordsCount(Object records) {
        if (records == null) return 0;
        return ReflectionUtils.invokeMethod(records, "count")
            .filter(v -> v instanceof Integer)
            .map(v -> (Integer) v)
            .orElse(0);
    }

    /** Returns the first topic name from a ConsumerRecords batch, or null if unavailable. */
    @SuppressWarnings("unchecked")
    public static String getFirstTopic(Object records) {
        if (records == null) return null;
        return ReflectionUtils.invokeMethod(records, "topics")
            .filter(v -> v instanceof Set)
            .map(v -> (Set<?>) v)
            .flatMap(set -> set.stream().findFirst())
            .map(Object::toString)
            .orElse(null);
    }

    /** Sets TxIdHolder only when txId is non-null/non-empty to avoid erasing existing txId. */
    public static void setTxIdIfPresent(String txId) {
        if (txId != null && !txId.isEmpty()) {
            TxIdHolder.clear();
            TxIdHolder.set(txId);
            AgentLogger.info("[MQ-CONSUME] Adopted incoming txId: " + txId);
        }
    }

    public static void injectHeader(Object record, String txId) {
        if (txId == null || record == null) return;
        AgentLogger.debug("[MQ-PRODUCE] Injecting txId into record headers: " + txId);
        boolean[] injected = {false};
        ReflectionUtils.invokeMethod(record, "headers").ifPresent(headers -> {
            ReflectionUtils.invokeMethod(headers, "add",
                AgentConfig.getHeaderKey(), txId.getBytes(StandardCharsets.UTF_8));
            injected[0] = true;
        });
        if (!injected[0]) {
            AgentLogger.debug("[MQ-PRODUCE] Failed to inject txId header — record.headers() returned empty");
        }
    }

    public static String extractTxId(Object record) {
        if (record == null) return null;
        String txId = ReflectionUtils.invokeMethod(record, "headers")
            .flatMap(h -> ReflectionUtils.invokeMethod(h, "lastHeader", AgentConfig.getHeaderKey()))
            .flatMap(header -> ReflectionUtils.invokeMethod(header, "value"))
            .map(val -> new String((byte[]) val, StandardCharsets.UTF_8))
            .orElse(null);
        AgentLogger.debug("[MQ-CONSUME] extractTxId from record("
            + record.getClass().getSimpleName() + "): " + txId);
        return txId;
    }

    public static String extractTopic(Object record) {
        return ReflectionUtils.invokeMethod(record, "topic")
            .map(Object::toString)
            .orElse(null);
    }

    public static String extractKey(Object record) {
        return ReflectionUtils.invokeMethod(record, "key")
            .map(String::valueOf)
            .orElse(null);
    }
}
