package org.example.agent.config;

/**
 * Spring Framework version profiles for bytecode instrumentation.
 *
 * <p>Each profile captures the class-level paths and API shape differences
 * that the trace-agent must account for when instrumenting a target application.
 *
 * <h3>Version summary</h3>
 * <pre>
 *  Spring | Boot    | Servlet NS  | org.springframework.* paths | doExecute arity
 * --------+---------+-------------+-----------------------------+------------------
 *  5.x    | 2.x     | javax       | unchanged across versions   | 4-param
 *  6.0    | 3.0-3.1 | jakarta     | unchanged across versions   | 4-param
 *  6.1+   | 3.2+    | jakarta     | unchanged across versions   | 5-param (*)
 * </pre>
 *
 * <p>(*) Spring 6.1 added an extra {@code String httpMethod} parameter to
 * {@code RestTemplate.doExecute}.
 * {@code RestClient} was introduced in 6.1 and is used as a detection marker.
 *
 * <p><b>Auto-detection</b>: call {@link #detect(ClassLoader)} with a suitable
 * classloader (e.g., from within a {@link java.lang.instrument.ClassFileTransformer}
 * callback) to determine the profile at runtime without requiring manual
 * {@code spring.version} configuration.
 */
public enum SpringVersionProfile {

    /**
     * Spring 5.x / Spring Boot 2.x.
     * <ul>
     *   <li>Servlet API: {@code javax.servlet.*}
     *   <li>{@code RestTemplate.doExecute}: 4-param form
     *       {@code (URI, HttpMethod, RequestCallback, ResponseExtractor)}
     * </ul>
     */
    SPRING_5(
        "org/springframework/web/servlet/DispatcherServlet",
        "org/springframework/web/client/RestTemplate",
        "org/springframework/web/reactive/function/client/ExchangeFunctions",
        "javax/servlet/http",
        false
    ),

    /**
     * Spring 6.0 / Spring Boot 3.0–3.1.
     * <ul>
     *   <li>Servlet API migrated to {@code jakarta.servlet.*}
     *   <li>{@code RestTemplate.doExecute}: 4-param form (unchanged from Spring 5)
     * </ul>
     */
    SPRING_6_0(
        "org/springframework/web/servlet/DispatcherServlet",
        "org/springframework/web/client/RestTemplate",
        "org/springframework/web/reactive/function/client/ExchangeFunctions",
        "jakarta/servlet/http",
        false
    ),

    /**
     * Spring 6.1+ / Spring Boot 3.2+.
     * <ul>
     *   <li>Servlet API: {@code jakarta.servlet.*}
     *   <li>{@code RestTemplate.doExecute}: 5-param form introduced in 6.1 —
     *       {@code (URI, String, HttpMethod, RequestCallback, ResponseExtractor)}
     *   <li>{@code RestClient} introduced in 6.1 (used as detection marker)
     * </ul>
     */
    SPRING_6_1(
        "org/springframework/web/servlet/DispatcherServlet",
        "org/springframework/web/client/RestTemplate",
        "org/springframework/web/reactive/function/client/ExchangeFunctions",
        "jakarta/servlet/http",
        true
    );

    // -----------------------------------------------------------------------
    // Profile fields
    // -----------------------------------------------------------------------

    /** Internal-form class name of DispatcherServlet (slash-separated, no .class). */
    public final String dispatcherServletClass;

    /** Internal-form class name of RestTemplate. */
    public final String restTemplateClass;

    /**
     * Prefix for the WebClient exchange-function implementation class.
     * A prefix (not full name) is used so that inner-class renames across
     * Spring versions are tolerated by a {@code startsWith()} check.
     */
    public final String webClientClassPrefix;

    /**
     * Servlet API package in internal (slash-separated) form.
     * <ul>
     *   <li>Spring 5: {@code javax/servlet/http}
     *   <li>Spring 6+: {@code jakarta/servlet/http}
     * </ul>
     */
    public final String servletPackage;

    /**
     * {@code true} when {@code RestTemplate.doExecute} uses the 5-parameter
     * signature introduced in Spring 6.1:
     * {@code doExecute(URI, String, HttpMethod, RequestCallback, ResponseExtractor)}.
     * {@code false} for the classic 4-parameter form present in Spring 5 and 6.0.
     */
    public final boolean restTemplate61Signature;

    SpringVersionProfile(
        String dispatcherServletClass,
        String restTemplateClass,
        String webClientClassPrefix,
        String servletPackage,
        boolean restTemplate61Signature
    ) {
        this.dispatcherServletClass  = dispatcherServletClass;
        this.restTemplateClass       = restTemplateClass;
        this.webClientClassPrefix    = webClientClassPrefix;
        this.servletPackage          = servletPackage;
        this.restTemplate61Signature = restTemplate61Signature;
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Resolves a profile from the {@code spring.version} configuration value.
     *
     * <p>Supported values:
     * <ul>
     *   <li>{@code "5"} or {@code "5.x"} → {@link #SPRING_5}
     *   <li>{@code "6"} or {@code "6.0"} → {@link #SPRING_6_0}
     *   <li>{@code "6.1"}, {@code "6.2"}, {@code "7"}, … → {@link #SPRING_6_1}
     * </ul>
     *
     * <p>Returns {@link #SPRING_5} for unrecognised or {@code null} values.
     */
    public static SpringVersionProfile fromConfig(String version) {
        if (version == null) return SPRING_5;
        String v = version.trim();
        if (v.startsWith("6.1") || v.startsWith("6.2") || v.startsWith("7")) return SPRING_6_1;
        if (v.startsWith("6")) return SPRING_6_0;
        return SPRING_5;
    }

    /**
     * Best-effort auto-detection of the Spring version using a classloader
     * that has access to the target application's classpath.
     *
     * <p>Detection steps:
     * <ol>
     *   <li>Check for {@code jakarta.servlet.http.HttpServletRequest} — present
     *       means Spring 6+.
     *   <li>If neither jakarta nor javax servlet is loadable, returns {@code null}
     *       (inconclusive — caller should retry with a different loader or fall back to config).
     *   <li>If jakarta is present, check for {@code org.springframework.web.client.RestClient}
     *       (introduced in Spring 6.1) to distinguish 6.0 from 6.1+.
     * </ol>
     *
     * @param loader a classloader expected to have servlet and Spring classes
     * @return the detected profile, or {@code null} if detection is inconclusive
     */
    public static SpringVersionProfile detect(ClassLoader loader) {
        if (loader == null) return null;

        boolean hasJakarta = isLoadable(loader, "jakarta.servlet.http.HttpServletRequest");
        boolean hasJavax   = isLoadable(loader, "javax.servlet.http.HttpServletRequest");

        if (!hasJakarta && !hasJavax) return null;
        if (!hasJakarta) return SPRING_5;

        boolean hasRestClient = isLoadable(loader, "org.springframework.web.client.RestClient");
        return hasRestClient ? SPRING_6_1 : SPRING_6_0;
    }

    private static boolean isLoadable(ClassLoader loader, String className) {
        try {
            loader.loadClass(className);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
