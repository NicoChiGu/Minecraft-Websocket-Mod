package dev.terata.mctunnel.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @TempDir Path temporaryDirectory;

    @Test
    void oldConfigWithoutUpdateKeyDefaultsToEnabled() throws Exception {
        Path file = temporaryDirectory.resolve("config.toml");
        Files.writeString(file, "bind-port = 8080\ntoken = \"test\"\n");

        ServerConfig config = ServerConfig.load(file);
        assertTrue(config.checkForUpdates());
        assertEquals(1, config.listeners().size());
        assertEquals(8080, config.listeners().get(0).bindPort());
        assertEquals("test", config.listeners().get(0).token());
        assertTrue(config.listeners().get(0).isWebSocket());
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
        assertTrue(config.isWebSocketMode());
        assertFalse(config.isGrpcMode());
        assertEquals(1, config.listeners().size());
        assertEquals(8080, config.listeners().get(0).bindPort());
    }

    @Test
    void canParseGrpcMode() throws Exception {
        Path file = temporaryDirectory.resolve("config.toml");
        Files.writeString(file, "mode = \"grpc\"\nbind-port = 50051\n");
        ServerConfig config = ServerConfig.load(file);
        assertTrue(config.isGrpcMode());
        assertFalse(config.isWebSocketMode());
        assertEquals("grpc", config.mode());
        assertEquals(1, config.listeners().size());
        assertTrue(config.listeners().get(0).isGrpc());
        assertEquals(50051, config.listeners().get(0).bindPort());
    }

    @Test
    void bothModeCreatesWebSocketAndGrpcListeners() throws Exception {
        Path file = temporaryDirectory.resolve("config.toml");
        Files.writeString(file, "mode = \"both\"\nbind-port = 8080\ntoken = \"my-tok\"\n");
        ServerConfig config = ServerConfig.load(file);
        assertEquals(2, config.listeners().size());

        ListenerConfig ws = config.listeners().get(0);
        assertTrue(ws.isWebSocket());
        assertEquals(8080, ws.bindPort());
        assertEquals("my-tok", ws.token());

        ListenerConfig grpc = config.listeners().get(1);
        assertTrue(grpc.isGrpc());
        assertEquals(8081, grpc.bindPort());
        assertEquals("my-tok", grpc.token());

        assertTrue(config.validate().isEmpty());
    }

    @Test
    void canParseMultipleListenersWithDifferentSettings() throws Exception {
        Path file = temporaryDirectory.resolve("config.toml");
        String content = String.join("\n",
            "target-host = \"127.0.0.1\"",
            "target-port = 25565",
            "",
            "listener.1.mode = \"websocket\"",
            "listener.1.bind-host = \"0.0.0.0\"",
            "listener.1.bind-port = 8080",
            "listener.1.path = \"/tunnel\"",
            "listener.1.token = \"token-ws-1\"",
            "",
            "listener.2.mode = \"websocket\"",
            "listener.2.bind-host = \"127.0.0.1\"",
            "listener.2.bind-port = 8081",
            "listener.2.path = \"/vip\"",
            "listener.2.token = \"token-ws-2\"",
            "",
            "listener.3.mode = \"grpc\"",
            "listener.3.bind-host = \"0.0.0.0\"",
            "listener.3.bind-port = 50051",
            "listener.3.token = \"token-grpc\""
        );
        Files.writeString(file, content);

        ServerConfig config = ServerConfig.load(file);
        List<ListenerConfig> listeners = config.listeners();
        assertEquals(3, listeners.size());

        ListenerConfig l1 = listeners.get(0);
        assertTrue(l1.isWebSocket());
        assertEquals("0.0.0.0", l1.bindHost());
        assertEquals(8080, l1.bindPort());
        assertEquals("/tunnel", l1.path());
        assertEquals("token-ws-1", l1.token());

        ListenerConfig l2 = listeners.get(1);
        assertTrue(l2.isWebSocket());
        assertEquals("127.0.0.1", l2.bindHost());
        assertEquals(8081, l2.bindPort());
        assertEquals("/vip", l2.path());
        assertEquals("token-ws-2", l2.token());

        ListenerConfig l3 = listeners.get(2);
        assertTrue(l3.isGrpc());
        assertEquals("0.0.0.0", l3.bindHost());
        assertEquals(50051, l3.bindPort());
        assertEquals("token-grpc", l3.token());

        assertTrue(config.validate().isEmpty());
    }

    @Test
    void detectsDuplicateBindPorts() {
        ServerConfig config = new ServerConfig(
            "websocket", "0.0.0.0", 8080, "127.0.0.1", 25565, "tok", "/tunnel", true,
            List.of(
                new ListenerConfig("websocket", "0.0.0.0", 8080, "/t1", "tok1"),
                new ListenerConfig("grpc", "0.0.0.0", 8080, "/t2", "tok2")
            )
        );
        List<String> errors = config.validate();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Duplicate bind port 8080"));
    }

    @Test
    void listenerOrderingIsDeterministicByNumericId() throws Exception {
        Path file = temporaryDirectory.resolve("config.toml");
        // Put listener 10 before listener 2 in file text
        String content = String.join("\n",
            "listener.10.mode = \"grpc\"",
            "listener.10.bind-port = 50051",
            "listener.2.mode = \"websocket\"",
            "listener.2.bind-port = 8080"
        );
        Files.writeString(file, content);

        ServerConfig config = ServerConfig.load(file);
        assertEquals(2, config.listeners().size());
        assertEquals(8080, config.listeners().get(0).bindPort());
        assertEquals(50051, config.listeners().get(1).bindPort());
    }
}
