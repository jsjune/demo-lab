package org.example.agent.core.handler;

import org.example.agent.core.util.AgentLogger;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.context.TxIdHolder;
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
    private static final ThreadLocal<DbCallState> DB_STATE = ThreadLocal.withInitial(DbCallState::fresh);
    private static final int ERROR_CHAIN_LIMIT = 3;

    private DbEventHandler() {}

    private static DbCallState state() { return DB_STATE.get(); }

    public static void onStart(String sql, String dbHost) {
        TraceRuntime.safeRun(() -> {
            String txId = TxIdHolder.get();
            if (txId == null) return;
            refreshTxContext(txId);
            DbCallState s = state();
            if (s.failureLatched) return;
            int depth = s.depth + 1;
            s.depth = depth;
            if (depth > 1) return;
            if (s.inFlight) return;
            s.inFlight = true;
            s.eventEmitted = false;
            s.startTs = System.currentTimeMillis();
            s.sql = TraceRuntime.truncate(sql);
            s.host = dbHost != null ? dbHost : "unknown-db";
        });
    }

    public static void onEnd(String sql, long durationMs, String dbHost) {
        TraceRuntime.safeRun(() -> {
            int depth = state().depth;
            if (depth <= 0) return;
            try {
                if (depth > 1) return;
                if (!state().inFlight) return;
                if (state().eventEmitted) return;
                String txId = TxIdHolder.get();
                if (txId == null) return;
                refreshTxContext(txId);
                if (state().failureLatched) return;
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
                state().eventEmitted = true;
            } finally {
                if (depth == 1) {
                    state().clearInFlightState();
                }
                releaseDepth();
            }
        });
    }

    public static void onError(Throwable t, String sql, long durationMs, String dbHost) {
        TraceRuntime.safeRun(() -> {
            int depth = state().depth;
            if (depth <= 0) {
                emitErrorEvent(t, sql, durationMs, dbHost);
                return;
            }
            try {
                if (depth > 1) return;
                if (state().eventEmitted) return;
                emitErrorEvent(t, sql, durationMs, dbHost);
            } finally {
                if (depth == 1) {
                    state().clearInFlightState();
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
        DbCallState s = state();
        if (s.depth <= 1) {
            s.depth = 0;
            return;
        }
        s.depth--;
    }

    static void resetForTest() {
        DB_STATE.remove();
    }

    private static void emitErrorEvent(Throwable t, String sql, long durationMs, String dbHost) {
        String txId = TxIdHolder.get();
        if (txId == null) return;
        refreshTxContext(txId);
        if (state().failureLatched) return;
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
        state().eventEmitted = true;
        state().failureLatched = true;
    }

    /**
     * Replaces the current thread's {@link DbCallState} with a fresh instance when the
     * transaction context has changed.
     *
     * <p><b>Stale-reference warning</b>: this method calls {@code DB_STATE.set(fresh)},
     * which replaces the object returned by {@code state()}. Any local variable that
     * captured {@code state()} <em>before</em> this call will point to the discarded
     * object. Always capture {@code DbCallState s = state()} <em>after</em> calling
     * this method — never before.
     */
    private static void refreshTxContext(String txId) {
        if (txId.equals(state().txContext)) return;
        DbCallState fresh = DbCallState.fresh();
        fresh.txContext = txId;
        DB_STATE.set(fresh);
    }

    private static String resolveSql(String candidateSql) {
        String fromArg = TraceRuntime.truncate(candidateSql);
        if (!isConnectionOnlySql(fromArg)) return fromArg;
        String fromCtx = state().sql;
        if (!isConnectionOnlySql(fromCtx)) return fromCtx;
        return "";
    }

    private static String resolveDbHost(String candidateDbHost) {
        if (candidateDbHost != null && !candidateDbHost.isBlank()) return candidateDbHost;
        String fromCtx = state().host;
        return (fromCtx != null && !fromCtx.isBlank()) ? fromCtx : "unknown-db";
    }

    private static long resolveDuration(long candidateDurationMs) {
        if (candidateDurationMs >= 0L) return candidateDurationMs;
        long start = state().startTs;
        if (start == 0L) return 0L;
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

    // -----------------------------------------------------------------------
    // DbCallState — single-object holder for all per-thread DB tracing state
    // -----------------------------------------------------------------------

    static final class DbCallState {
        int    depth          = 0;
        String txContext      = null;
        boolean inFlight      = false;
        boolean eventEmitted  = false;
        boolean failureLatched = false;
        long   startTs        = 0L;
        String sql            = null;
        String host           = null;

        private DbCallState() {}

        /** Returns a clean state instance for a new DB tracing context. */
        static DbCallState fresh() {
            return new DbCallState();
        }

        void clearInFlightState() {
            inFlight     = false;
            eventEmitted = false;
            startTs      = 0L;
            sql          = null;
            host         = null;
        }
    }
}
