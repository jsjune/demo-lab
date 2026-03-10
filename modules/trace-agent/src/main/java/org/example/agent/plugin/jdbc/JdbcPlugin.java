package org.example.agent.plugin.jdbc;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.example.agent.AgentInitializer;
import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.TraceIgnore;
import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.ReflectionUtils;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * JdbcPlugin: instruments PreparedStatement.execute*() for JDBC tracing.
 *
 * <p>Supported drivers: MySQL 5.x/8.x, PostgreSQL, H2, MariaDB, SQL Server, Oracle.
 * Uses ByteBuddy @Advice inline instrumentation — no raw ASM required.
 */
public class JdbcPlugin implements TracerPlugin {

    @Override public String pluginId() { return "jdbc"; }

    @Override
    public AgentBuilder install(AgentBuilder builder) {
        if (!isEnabled()) return builder;

        ClassFileLocator agentLocator = AgentInitializer.getAgentLocator();

        return builder
            .type(nameContains("PreparedStatement").and(
                nameStartsWith("com.mysql.jdbc.")
                    .or(nameStartsWith("com.mysql.cj.jdbc."))
                    .or(nameStartsWith("org.mariadb.jdbc."))
                    .or(nameStartsWith("org.postgresql.jdbc."))
                    .or(nameStartsWith("org.h2.jdbc."))
                    .or(nameStartsWith("com.microsoft.sqlserver.jdbc."))
                    .or(nameStartsWith("oracle.jdbc."))
            ))
            .transform((b, type, cl, m, pd) ->
                b.visit(Advice.to(JdbcStatementAdvice.class, agentLocator)
                    .on(namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")
                        .and(takesNoArguments())
                        // Skip methods annotated with @TraceIgnore
                        .and(not(isAnnotatedWith(TraceIgnore.class))))));
    }

    // -----------------------------------------------------------------------
    // Advice
    // -----------------------------------------------------------------------

    public static class JdbcStatementAdvice {

        @Advice.OnMethodEnter
        static void enter(
            @Advice.This Object statement,
            @Advice.Local("sql") String sql,
            @Advice.Local("dbHost") String dbHost,
            @Advice.Local("startTime") long startTime
        ) {
            sql = JdbcPlugin.extractSql(statement);
            dbHost = JdbcPlugin.extractDbHost(statement);
            startTime = System.currentTimeMillis();
            TraceRuntime.onDbQueryStart(sql, dbHost);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("sql") String sql,
            @Advice.Local("dbHost") String dbHost,
            @Advice.Local("startTime") long startTime
        ) {
            long durationMs = System.currentTimeMillis() - startTime;
            if (thrown != null) {
                TraceRuntime.onDbQueryError(thrown, sql, durationMs, dbHost);
            } else {
                TraceRuntime.onDbQueryEnd(sql, durationMs, dbHost);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Static helpers — called from injected bytecode (ABI stable)
    // -----------------------------------------------------------------------

    public static String extractSql(Object statement) {
        if (statement == null) return "unknown-sql";
        String className = statement.getClass().getName();
        if (className.contains("mysql")) {
            return ReflectionUtils.getFieldValue(statement, "query")
                .map(Object::toString)
                .orElseGet(statement::toString);
        }
        return statement.toString();
    }

    public static String extractDbHost(Object statement) {
        if (statement == null) return "unknown-db";
        try {
            Object connection = ReflectionUtils.invokeMethod(statement, "getConnection").orElse(null);
            return extractDbHostFromConnection(connection);
        } catch (Throwable e) {
            return "unknown-db";
        }
    }

    public static String extractDbHostFromConnection(Object connection) {
        if (connection == null) return "unknown-db";
        try {
            Object metaData = ReflectionUtils.invokeMethod(connection, "getMetaData").orElse(null);
            if (metaData == null) return "unknown-db";
            Object url = ReflectionUtils.invokeMethod(metaData, "getURL").orElse(null);
            if (url == null) return "unknown-db";
            return parseDbHost(url.toString());
        } catch (Throwable e) {
            return "unknown-db";
        }
    }

    public static String parseDbHost(String jdbcUrl) {
        if (jdbcUrl == null) return "unknown-db";
        try {
            String url = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
            int schemeEnd = url.indexOf(':');
            if (schemeEnd < 0) return url;
            String scheme = url.substring(0, schemeEnd);
            String rest = url.substring(schemeEnd + 1);
            if (rest.startsWith("//")) {
                String hostPart = rest.substring(2);
                int atIdx = hostPart.indexOf('@');
                if (atIdx >= 0) hostPart = hostPart.substring(atIdx + 1);
                int slashIdx = hostPart.indexOf('/');
                if (slashIdx > 0) hostPart = hostPart.substring(0, slashIdx);
                int semiIdx = hostPart.indexOf(';');
                if (semiIdx > 0) hostPart = hostPart.substring(0, semiIdx);
                return scheme + "://" + hostPart;
            }
            return scheme + ":" + (rest.length() > 30 ? rest.substring(0, 30) + "..." : rest);
        } catch (Throwable e) {
            return jdbcUrl.length() > 50 ? jdbcUrl.substring(0, 50) : jdbcUrl;
        }
    }
}
