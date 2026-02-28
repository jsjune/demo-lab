package org.example.agent.config;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public class AgentConfig {
    private static final Properties props = new Properties();

    /**
     * Active Spring version profile.
     * <ul>
     *   <li>Set eagerly from {@code spring.version} property during {@link #init()}.
     *   <li>Set lazily via {@link #updateProfileFromLoader(ClassLoader)} on the first
     *       transform call when no explicit version is configured.
     *   <li>Falls back to {@link SpringVersionProfile#SPRING_5} if still unresolved.
     * </ul>
     * Volatile for safe publication across the instrumentation thread and app threads.
     */
    private static volatile SpringVersionProfile resolvedProfile = null;

    static {
        loadDefaultProperties();
    }

    private static void loadDefaultProperties() {
        try (InputStream is = AgentConfig.class.getResourceAsStream("/trace-agent.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (Exception e) {
            System.err.println("[TRACE AGENT] Failed to load default properties: " + e.getMessage());
        }
    }

    public static synchronized void init() {
        // 1) External config file override: -Dtrace.agent.config=/path/to/file.properties
        String externalConfig = System.getProperty("trace.agent.config");
        if (externalConfig != null && !externalConfig.isEmpty()) {
            try (InputStream is = new FileInputStream(externalConfig)) {
                props.load(is);
            } catch (Exception e) {
                System.err.println("[TRACE AGENT] Failed to load external config '"
                    + externalConfig + "': " + e.getMessage());
            }
        }

        // 2) System Property highest-priority override: -Dtrace.agent.xxx=yyy
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            String key = entry.getKey().toString();
            if (key.startsWith("trace.agent.") && !key.equals("trace.agent.config")) {
                props.setProperty(key.substring("trace.agent.".length()), entry.getValue().toString());
            }
        }

        // 3) Resolve version profile from explicit config if provided.
        //    If spring.version is absent, profile remains null until auto-detected
        //    at transform time via updateProfileFromLoader().
        String explicitVersion = props.getProperty("spring.version");
        if (explicitVersion != null && !explicitVersion.trim().isEmpty()) {
            resolvedProfile = SpringVersionProfile.fromConfig(explicitVersion);
        }
    }

    // -----------------------------------------------------------------------
    // Spring version profile
    // -----------------------------------------------------------------------

    /**
     * Returns the active {@link SpringVersionProfile}.
     * Falls back to {@link SpringVersionProfile#SPRING_5} when auto-detection
     * has not yet completed (safe default — avoids null checks in plugins).
     */
    public static SpringVersionProfile getSpringVersionProfile() {
        SpringVersionProfile p = resolvedProfile;
        return p != null ? p : SpringVersionProfile.SPRING_5;
    }

    /**
     * Called from {@code CompositeTransformer} to supply the auto-detected profile
     * the first time a classloader with Spring on its classpath is encountered.
     * Has no effect once a profile has already been resolved (explicit config wins).
     */
    public static void updateProfileFromLoader(ClassLoader loader) {
        if (resolvedProfile != null) return;
        SpringVersionProfile detected = SpringVersionProfile.detect(loader);
        if (detected != null) {
            resolvedProfile = detected;
        }
    }

    /** Returns {@code true} once the version profile has been resolved. */
    public static boolean isSpringVersionResolved() {
        return resolvedProfile != null;
    }

    // -----------------------------------------------------------------------
    // HTTP plugin: class path resolution
    //
    // Priority:
    //   1. Explicit property override (http.dispatcher.class etc.) — escape hatch
    //      for exotic setups or future Spring reorganisations.
    //   2. Active SpringVersionProfile (set from config or auto-detected).
    // -----------------------------------------------------------------------

    public static String getHttpDispatcherClass() {
        String explicit = props.getProperty("http.dispatcher.class");
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return getSpringVersionProfile().dispatcherServletClass;
    }

    public static String getHttpRestTemplateClass() {
        String explicit = props.getProperty("http.resttemplate.class");
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return getSpringVersionProfile().restTemplateClass;
    }

    public static String getHttpAccessorClass() {
        String explicit = props.getProperty("http.accessor.class");
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return "org/springframework/http/client/support/HttpAccessor";
    }

    /**
     * Prefix for the WebClient exchange-function implementation class.
     * A prefix (not full name) is used so that inner-class renames between
     * Spring versions are handled automatically by the {@code startsWith()} check
     * in {@code WebClientTransformer}.
     */
    public static String getHttpWebClientClassPrefix() {
        String explicit = props.getProperty("http.webclient.class.prefix");
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return getSpringVersionProfile().webClientClassPrefix;
    }

    /**
     * Servlet API package in internal (slash-separated) form derived from the
     * active {@link SpringVersionProfile}.
     * <ul>
     *   <li>Spring 5: {@code javax/servlet/http}
     *   <li>Spring 6+: {@code jakarta/servlet/http}
     * </ul>
     */
    public static String getServletPackage() {
        return getSpringVersionProfile().servletPackage;
    }

    // -----------------------------------------------------------------------
    // General config
    // -----------------------------------------------------------------------

    public static String getHeaderKey()           { return get("header-key", "X-Tx-Id"); }
    public static String getForceSampleHeader()   { return get("force-sample-header", "X-Trace-Force"); }
    public static String getServerName()     { return get("server-name", "unknown-server"); }
    public static String getCollectorHost()  { return get("collector.host", "localhost"); }
    public static int    getCollectorPort()  { return getInt("collector.port", 9200); }

    public static double getSamplingRate() {
        try { return Double.parseDouble(get("sampling.rate", "1.0")); } catch (Exception e) { return 1.0; }
    }

    public static String getSamplingStrategy() { return get("sampling.strategy", "rate"); }

    public static boolean isPluginEnabled(String pluginId) {
        return Boolean.parseBoolean(get("plugin." + pluginId + ".enabled", "true"));
    }

    public static int  getBufferCapacity() { return getInt("buffer.capacity", 1000); }
    public static long getSlowQueryMs()  { return getLong("slow-query-ms", 500); }
    public static long getMinSizeBytes() { return getLong("min-size-bytes", 1024); }

    // -----------------------------------------------------------------------
    // Logging config
    // -----------------------------------------------------------------------
    public static String getLogFilePath()  { return get("log.file.path", "logs/trace-agent.log"); }
    public static int    getLogFileLimit() { return getInt("log.file.limit", 5 * 1024 * 1024); } // 5MB
    public static int    getLogFileCount() { return getInt("log.file.count", 5); }
    public static String getLogLevel()     { return get("log.level", "INFO"); }

    public static String get(String key, String defaultValue) { return props.getProperty(key, defaultValue); }

    public static int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        try { return val != null ? Integer.parseInt(val) : defaultValue; } catch (Exception e) { return defaultValue; }
    }

    public static long getLong(String key, long defaultValue) {
        String val = props.getProperty(key);
        try { return val != null ? Long.parseLong(val) : defaultValue; } catch (Exception e) { return defaultValue; }
    }
}
