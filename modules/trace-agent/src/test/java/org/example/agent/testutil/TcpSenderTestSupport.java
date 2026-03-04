package org.example.agent.testutil;

import org.example.agent.core.TcpSender;
import org.example.common.TraceEvent;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Shared helper for manipulating TcpSender static state in tests.
 */
public final class TcpSenderTestSupport {
    private static final String SINGLE_SENDER_THREAD = "trace-agent-sender";
    private static final String BATCH_SENDER_THREAD = "trace-agent-batch-sender";

    private TcpSenderTestSupport() {}

    public static void resetState() throws Exception {
        stopSenderThreads(500);
        setInitialized(false);
        setQueue(null);
    }

    public static void stopSenderThreads(long joinTimeoutMs) throws InterruptedException {
        stopThreadByName(SINGLE_SENDER_THREAD, joinTimeoutMs);
        stopThreadByName(BATCH_SENDER_THREAD, joinTimeoutMs);
    }

    public static boolean waitForThreadAlive(String threadName, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            boolean found = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> threadName.equals(t.getName()) && t.isAlive());
            if (found) return true;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static BlockingQueue<TraceEvent> getQueue() throws Exception {
        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        return (BlockingQueue<TraceEvent>) queueField.get(null);
    }

    public static void setQueue(BlockingQueue<TraceEvent> queue) throws Exception {
        Field queueField = TcpSender.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        queueField.set(null, queue);
    }

    public static long getDropCounterValue() throws Exception {
        Field dropCounterField = TcpSender.class.getDeclaredField("dropCounter");
        dropCounterField.setAccessible(true);
        return ((AtomicLong) dropCounterField.get(null)).get();
    }

    private static void setInitialized(boolean initialized) throws Exception {
        Field initField = TcpSender.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        initField.set(null, initialized);
    }

    private static void stopThreadByName(String name, long joinTimeoutMs) throws InterruptedException {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive()) {
                t.interrupt();
                t.join(joinTimeoutMs);
            }
        }
    }
}
