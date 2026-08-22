package dev.terata.mctunnel.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ClientConfig {
    public String gateway = "ws://127.0.0.1:8080/tunnel";
    public String token = "change-me";
    public String remoteName = "Minecraft Server";
    public int localPort = 25566;

    public ClientConfig() { }

    public ClientConfig(String gateway, String token, String remoteName, int localPort) {
        this.gateway = gateway;
        this.token = token;
        this.remoteName = remoteName;
        this.localPort = localPort;
    }

    public ClientConfig(ClientConfig source) {
        this(source.gateway, source.token, source.remoteName, source.localPort);
    }

    public ClientConfig copy() {
        return new ClientConfig(this);
    }

    public static ClientConfig load(Path file) {
        ClientConfig config = new ClientConfig();
        if (!Files.exists(file)) return config;
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
            config.gateway = p.getProperty("gateway", config.gateway);
            config.token = p.getProperty("token", config.token);
            config.remoteName = p.getProperty("remoteName", config.remoteName);
            try { config.localPort = Integer.parseInt(p.getProperty("localPort", Integer.toString(config.localPort))); }
            catch (NumberFormatException ignored) { }
        } catch (IOException ignored) { }
        return config;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("gateway", gateway);
        p.setProperty("token", token);
        p.setProperty("remoteName", remoteName);
        p.setProperty("localPort", Integer.toString(localPort));
        try (var out = Files.newOutputStream(file)) { p.store(out, "Minecraft Tunnel client configuration"); }
    }

    public boolean isGrpc() {
        return gateway != null && (gateway.startsWith("grpc://") || gateway.startsWith("grpcs://"));
    }

    public String protocolName() {
        return isGrpc() ? "gRPC" : "WebSocket";
    }
}
