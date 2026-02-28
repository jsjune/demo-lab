package org.example.agent.core;

import org.example.agent.config.AgentConfig;

import java.util.concurrent.ThreadLocalRandom;

public class TxIdGenerator {

    public static String generate() {
        String serverName = AgentConfig.getServerName();
        return serverName + "-" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    }

    /**
     * Sampling decision.
     * <ul>
     *   <li>{@code rate}: returns true with probability {@code sampling.rate} (default)
     *   <li>{@code error-only}: always returns true — success filtering happens upstream
     *       at {@code onHttpInEnd}/{@code onMqConsumeEnd} via the {@code success} flag
     * </ul>
     */
    public static boolean shouldSample() {
        String strategy = AgentConfig.getSamplingStrategy();
        if ("error-only".equals(strategy)) return true;
        double rate = AgentConfig.getSamplingRate();
        if (rate >= 1.0) return true;
        if (rate <= 0.0) return false;
        return ThreadLocalRandom.current().nextDouble() < rate;
    }
}
