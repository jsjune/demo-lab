package org.example.agent.core.context;

public class TxIdHolder {
    private static final ThreadLocal<String> holder = new ThreadLocal<>();

    public static void set(String txId) { holder.set(txId); }
    public static String get() { return holder.get(); }
    public static void clear() { holder.remove(); }
}
