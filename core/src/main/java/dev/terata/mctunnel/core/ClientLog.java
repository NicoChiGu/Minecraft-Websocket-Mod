package dev.terata.mctunnel.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Thread-safe in-memory client log shared by all Minecraft adapters. */
public final class ClientLog {
    public enum Level { INFO, WARN, ERROR }

    public record Entry(long timestampMillis, Level level, String message) { }

    private static final int MAX_ENTRIES = 500;
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private ClientLog() { }

    public static void info(String message) { add(Level.INFO, message); }
    public static void warn(String message) { add(Level.WARN, message); }
    public static void error(String message) { add(Level.ERROR, message); }

    public static synchronized void add(Level level, String message) {
        String safe = message == null || message.isBlank() ? "-" : message;
        ENTRIES.addLast(new Entry(System.currentTimeMillis(), level, safe));
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }
}
