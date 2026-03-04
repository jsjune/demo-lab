package org.example.agent.core;

/**
 * ThreadLocal holder for the current root span ID.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Set at: {@code onHttpInStart()}, {@code onMqConsumeStart()}
 *   <li>Read at: {@code onHttpInEnd()}, {@code onHttpInError()},
 *       {@code onMqConsumeEnd()}, {@code onMqConsumeError()},
 *       and all child-span-creating methods (DB, Cache, HTTP_OUT, MQ_PRODUCE)
 *   <li>Cleared at: {@code onHttpInEnd()}, {@code onHttpInError()},
 *       {@code onMqConsumeEnd()}, {@code onMqConsumeError()} — always after TxIdHolder.clear()
 * </ul>
 */
public class SpanIdHolder {
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    public static String get()         { return HOLDER.get(); }
    public static void  set(String id) { HOLDER.set(id); }
    public static void  clear()        { HOLDER.remove(); }
}
