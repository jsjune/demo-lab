package org.example.agent.core.handler;

import org.example.agent.core.AgentLogger;
import org.example.agent.core.TcpSender;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.example.common.TraceCategory;
import org.example.common.TraceEventType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DbEventHandler {
    private static final ThreadLocal<Integer> DB_CALL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<String> DB_TX_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DB_IN_FLIGHT = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> DB_EVENT_EMITTED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> DB_FAILURE_LATCHED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Long> DB_START_TS = new ThreadLocal<>();
    private static final ThreadLocal<String> DB_SQL = new ThreadLocal<>();
    private static final ThreadLocal<String> DB_HOST = new ThreadLocal<>();
    private static final int ERROR_CHAIN_LIMIT = 3;

    private DbEventHandler() {}

    public static void onStart(String sql, String dbHost) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            refreshTxContext(txId);
            if (DB_FAILURE_LATCHED.get()) return;
            int depth = DB_CALL_DEPTH.get() + 1;
            DB_CALL_DEPTH.set(depth);
            if (depth > 1) return;
            if (DB_IN_FLIGHT.get()) return;
            DB_IN_FLIGHT.set(true);
            DB_EVENT_EMITTED.set(false);
            DB_START_TS.set(System.currentTimeMillis());
            DB_SQL.set(TraceRuntime.truncate(sql));
            DB_HOST.set(dbHost != null ? dbHost : "unknown-db");
        });
    }

    public static void onEnd(String sql, long durationMs, String dbHost) {
        TraceRuntime.safeRun(() -> {
            int depth = DB_CALL_DEPTH.get();
            if (depth <= 0) return;
            try {
                if (depth > 1) return;
                if (!DB_IN_FLIGHT.get()) return;
                if (DB_EVENT_EMITTED.get()) return;
                String txId = TxIdHolder.get();
                if (txId == null) return;
                refreshTxContext(txId);
                if (DB_FAILURE_LATCHED.get()) return;
                String finalSql = resolveSql(sql);
                if (isConnectionOnlySql(finalSql)) return;
                long finalDurationMs = resolveDuration(durationMs);
                String finalHost = resolveDbHost(dbHost);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("sql", finalSql);
                AgentLogger.debug("[TRACE][DB][DB_QUERY] txId=" + txId
                    + " dbHost=" + finalHost + " durationMs=" + finalDurationMs + " success=true");
                TraceRuntime.emitEvent(TraceRuntime.createChildEvent(txId, TraceEventType.DB_QUERY,
                        TraceCategory.DB, finalHost, finalDurationMs, true, extra));
                DB_EVENT_EMITTED.set(true);
            } finally {
                if (depth == 1) {
                    clearInFlightState();
                }
                releaseDepth();
            }
        });
    }

    public static void onError(Throwable t, String sql, long durationMs, String dbHost) {
        TraceRuntime.safeRun(() -> {
            int depth = DB_CALL_DEPTH.get();
            if (depth <= 0) {
                emitErrorEvent(t, sql, durationMs, dbHost);
                return;
            }
            try {
                if (depth > 1) return;
                if (DB_EVENT_EMITTED.get()) return;
                emitErrorEvent(t, sql, durationMs, dbHost);
            } finally {
                if (depth == 1) {
                    clearInFlightState();
                }
                releaseDepth();
            }
        });
    }

    // Empty/blank SQL is usually connection/driver internal noise.
    // Keep only error events for this class of DB activity.
    private static boolean isConnectionOnlySql(String sql) {
        return sql == null || sql.trim().isEmpty();
    }

    private static void releaseDepth() {
        int depth = DB_CALL_DEPTH.get();
        if (depth <= 1) {
            DB_CALL_DEPTH.remove();
            return;
        }
        DB_CALL_DEPTH.set(depth - 1);
    }

    static void resetDepthForTest() {
        DB_CALL_DEPTH.remove();
        DB_TX_CONTEXT.remove();
        DB_IN_FLIGHT.remove();
        DB_EVENT_EMITTED.remove();
        DB_FAILURE_LATCHED.remove();
        DB_START_TS.remove();
        DB_SQL.remove();
        DB_HOST.remove();
    }

    private static void emitErrorEvent(Throwable t, String sql, long durationMs, String dbHost) {
        String txId = TxIdHolder.get();
        if (txId == null) return;
        refreshTxContext(txId);
        if (DB_FAILURE_LATCHED.get()) return;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sql", resolveSql(sql));
        try {
            Throwable root = rootCauseOf(t);
            SQLException sqlException = firstSqlException(t);
            List<String> chain = summarizeErrorChain(t, ERROR_CHAIN_LIMIT);
            extra.put("errorType", root != null ? root.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", normalizeMessage(root));
            extra.put("errorClass", t != null ? t.getClass().getName() : "UnknownError");
            extra.put("rootCauseClass", root != null ? root.getClass().getName() : "UnknownError");
            extra.put("rootCauseMessage", normalizeMessage(root));
            extra.put("chainSummary", String.join(" | ", chain));
            extra.put("suppressedCount", suppressedCountOf(t));
            extra.put("sqlState", sqlException != null ? nullable(sqlException.getSQLState()) : "");
            extra.put("vendorCode", sqlException != null ? String.valueOf(sqlException.getErrorCode()) : "");
        } catch (Throwable aggregationError) {
            extra.put("errorType", t != null ? t.getClass().getSimpleName() : "UnknownError");
            extra.put("errorMessage", (t != null && t.getMessage() != null) ? TraceRuntime.truncate(t.getMessage()) : "");
            extra.put("errorClass", t != null ? t.getClass().getName() : "UnknownError");
            extra.put("rootCauseClass", t != null ? t.getClass().getName() : "UnknownError");
            extra.put("rootCauseMessage", (t != null && t.getMessage() != null) ? TraceRuntime.truncate(t.getMessage()) : "");
            extra.put("chainSummary", extra.get("errorType") + ": " + extra.get("errorMessage"));
            extra.put("suppressedCount", 0);
            extra.put("sqlState", "");
            extra.put("vendorCode", "");
            extra.put("aggregationError", aggregationError.getClass().getSimpleName());
        }
        AgentLogger.debug("[TRACE][DB][DB_QUERY] txId=" + txId
            + " dbHost=" + resolveDbHost(dbHost) + " durationMs=" + resolveDuration(durationMs) + " success=false"
            + " errorType=" + extra.get("errorType")
            + " errorMessage=" + extra.get("errorMessage")
            + " chainSummary=" + extra.get("chainSummary"));
        TraceRuntime.emitEvent(TraceRuntime.createChildEvent(txId, TraceEventType.DB_QUERY,
                TraceCategory.DB, resolveDbHost(dbHost), resolveDuration(durationMs), false, extra));
        DB_EVENT_EMITTED.set(true);
        DB_FAILURE_LATCHED.set(true);
    }

    private static void refreshTxContext(String txId) {
        String current = DB_TX_CONTEXT.get();
        if (txId.equals(current)) return;
        DB_TX_CONTEXT.set(txId);
        clearInFlightState();
        DB_FAILURE_LATCHED.set(false);
        DB_CALL_DEPTH.set(0);
    }

    private static void clearInFlightState() {
        DB_IN_FLIGHT.set(false);
        DB_EVENT_EMITTED.set(false);
        DB_START_TS.remove();
        DB_SQL.remove();
        DB_HOST.remove();
    }

    private static String resolveSql(String candidateSql) {
        String fromArg = TraceRuntime.truncate(candidateSql);
        if (!isConnectionOnlySql(fromArg)) return fromArg;
        String fromCtx = DB_SQL.get();
        if (!isConnectionOnlySql(fromCtx)) return fromCtx;
        return "";
    }

    private static String resolveDbHost(String candidateDbHost) {
        if (candidateDbHost != null && !candidateDbHost.isBlank()) return candidateDbHost;
        String fromCtx = DB_HOST.get();
        return (fromCtx != null && !fromCtx.isBlank()) ? fromCtx : "unknown-db";
    }

    private static long resolveDuration(long candidateDurationMs) {
        if (candidateDurationMs >= 0L) return candidateDurationMs;
        Long start = DB_START_TS.get();
        if (start == null) return 0L;
        long elapsed = System.currentTimeMillis() - start;
        return Math.max(0L, elapsed);
    }

    private static Throwable rootCauseOf(Throwable t) {
        if (t == null) return null;
        Throwable current = t;
        Set<Throwable> seen = new HashSet<>();
        while (current.getCause() != null && seen.add(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    private static SQLException firstSqlException(Throwable t) {
        Throwable current = t;
        Set<Throwable> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (current instanceof SQLException) return (SQLException) current;
            current = current.getCause();
        }
        return null;
    }

    private static int suppressedCountOf(Throwable t) {
        if (t == null) return 0;
        return t.getSuppressed() != null ? t.getSuppressed().length : 0;
    }

    private static List<String> summarizeErrorChain(Throwable t, int limit) {
        List<String> out = new ArrayList<>();
        Throwable current = t;
        Set<Throwable> seen = new HashSet<>();
        while (current != null && seen.add(current) && out.size() < limit) {
            String className = current.getClass().getSimpleName();
            String message = normalizeMessage(current);
            if (message.isEmpty()) {
                out.add(className);
            } else {
                out.add(className + ": " + TraceRuntime.truncate(message));
            }
            current = current.getCause();
        }
        return out;
    }

    private static String normalizeMessage(Throwable t) {
        if (t == null || t.getMessage() == null) return "";
        return TraceRuntime.truncate(t.getMessage());
    }

    private static String nullable(String v) {
        return v != null ? v : "";
    }
}
