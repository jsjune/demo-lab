package org.example.agent.plugin.mq;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.example.agent.AgentInitializer;
import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.agent.plugin.ReflectionUtils;

import java.nio.charset.StandardCharsets;

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
