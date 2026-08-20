package dev.terata.mctunnel.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Standalone process that replaces the loaded mod JAR after the parent JVM exits. */
public final class UpdateInstaller {
    private UpdateInstaller() { }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target, boolean replace) throws IOException;
    }

    public static void main(String[] args) {
        if (args.length != 5) return;
        Path current = Path.of(args[1]).toAbsolutePath().normalize();
        Path staged = Path.of(args[2]).toAbsolutePath().normalize();
        Path backup = Path.of(args[3]).toAbsolutePath().normalize();
        Path log = Path.of(args[4]).toAbsolutePath().normalize();
        try {
            long parentPid = Long.parseLong(args[0]);
            ProcessHandle.of(parentPid).ifPresent(handle -> handle.onExit().join());
            install(current, staged, backup);
            append(log, "Installed update at " + current);
        } catch (Exception e) {
            append(log, "Update installation failed: " + readableMessage(e));
        }
    }

    public static void install(Path current, Path staged, Path backup) throws IOException {
        install(current, staged, backup, UpdateInstaller::move);
    }

    static void install(Path current, Path staged, Path backup, MoveOperation mover) throws IOException {
        current = current.toAbsolutePath().normalize();
        staged = staged.toAbsolutePath().normalize();
        backup = backup.toAbsolutePath().normalize();
        if (!Files.isRegularFile(current)) throw new IOException("Current mod JAR does not exist: " + current);
        if (!Files.isRegularFile(staged)) throw new IOException("Staged update does not exist: " + staged);
        if (!current.getParent().equals(staged.getParent()) || !current.getParent().equals(backup.getParent())) {
            throw new IOException("Update files must share the current JAR directory");
        }

        Files.deleteIfExists(backup);
        mover.move(current, backup, false);
        try {
            mover.move(staged, current, true);
        } catch (IOException installFailure) {
            try {
                if (!Files.exists(current) && Files.exists(backup)) mover.move(backup, current, true);
            } catch (IOException rollbackFailure) {
                installFailure.addSuppressed(rollbackFailure);
            }
            throw installFailure;
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException ignored) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        }
    }

    private static void append(Path log, String message) {
        try {
            Path parent = log.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(log, Instant.now() + " " + message + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName() : e.getMessage();
    }
}
