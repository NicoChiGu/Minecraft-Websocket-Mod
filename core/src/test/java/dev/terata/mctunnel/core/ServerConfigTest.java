package dev.terata.mctunnel.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @TempDir Path temporaryDirectory;

    @Test
    void oldConfigWithoutUpdateKeyDefaultsToEnabled() throws Exception {
        Path file = temporaryDirectory.resolve("config.toml");
        Files.writeString(file, "bind-port = 8080\ntoken = \"test\"\n");

        assertTrue(ServerConfig.load(file).checkForUpdates());
    }

    @Test
    void updateCheckCanBeDisabled() throws Exception {
        Path file = temporaryDirectory.resolve("config.toml");
        Files.writeString(file, "check-for-updates = false\n");

        assertFalse(ServerConfig.load(file).checkForUpdates());
    }

    @Test
    void legacyConstructorKeepsUpdateChecksEnabled() {
        ServerConfig config = new ServerConfig("0.0.0.0", 8080, "127.0.0.1", 25565, "token", "/tunnel");
        assertTrue(config.checkForUpdates());
    }
}
