package dev.terata.mctunnel.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Tracks application-level heartbeat results without depending on wall-clock time. */
public final class HeartbeatTracker {
    private final int windowSize;
    private final Map<Long, Long> pending = new HashMap<>();
    private final ArrayDeque<Boolean> outcomes = new ArrayDeque<>();
    private int losses;

    public HeartbeatTracker(int windowSize) {
        if (windowSize < 1) throw new IllegalArgumentException("Window size must be positive");
        this.windowSize = windowSize;
    }

    public synchronized void recordSent(long token, long sentAtNanos) {
        pending.put(token, sentAtNanos);
    }

    /** Returns the round-trip duration in nanoseconds, or {@code -1} for an unknown/late token. */
    public synchronized long recordPong(long token, long receivedAtNanos) {
        Long sentAt = pending.remove(token);
        if (sentAt == null) return -1L;
        append(false);
        return Math.max(0L, receivedAtNanos - sentAt);
    }

    public synchronized void expire(long nowNanos, long timeoutNanos) {
        if (timeoutNanos < 0) throw new IllegalArgumentException("Timeout cannot be negative");
        Iterator<Map.Entry<Long, Long>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            if (nowNanos - entry.getValue() >= timeoutNanos) {
                iterator.remove();
                append(true);
            }
        }
    }

    public synchronized int lossPercent() {
        if (outcomes.isEmpty()) return -1;
        return Math.round(losses * 100.0F / outcomes.size());
    }

    public synchronized int completedSamples() {
        return outcomes.size();
    }

    public synchronized int pendingSamples() {
        return pending.size();
    }

    public synchronized void reset() {
        pending.clear();
        outcomes.clear();
        losses = 0;
    }

    private void append(boolean lost) {
        outcomes.addLast(lost);
        if (lost) losses++;
        while (outcomes.size() > windowSize) {
            if (outcomes.removeFirst()) losses--;
        }
    }
}
