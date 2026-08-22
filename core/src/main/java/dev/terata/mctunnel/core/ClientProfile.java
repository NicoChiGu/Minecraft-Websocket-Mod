package dev.terata.mctunnel.core;

import java.util.UUID;

/** A saved tunnel configuration. The id is stable even when the display name changes. */
public record ClientProfile(
    UUID id,
    String gateway,
    String token,
    String remoteName,
    int localPort
) {
    public ClientProfile {
        if (id == null) throw new IllegalArgumentException("Profile id is required");
        gateway = gateway == null ? "" : gateway;
        token = token == null ? "" : token;
        remoteName = remoteName == null || remoteName.isBlank() ? "Minecraft Server" : remoteName;
    }

    public static ClientProfile createDefault() {
        return fromConfig(UUID.randomUUID(), new ClientConfig());
    }

    public static ClientProfile fromConfig(UUID id, ClientConfig config) {
        return new ClientProfile(id, config.gateway, config.token, config.remoteName, config.localPort);
    }

    public ClientConfig toConfig() {
        return new ClientConfig(gateway, token, remoteName, localPort);
    }

    public boolean isGrpc() {
        return gateway != null && (gateway.startsWith("grpc://") || gateway.startsWith("grpcs://"));
    }

    public String protocolName() {
        return isGrpc() ? "gRPC" : "WebSocket";
    }

    public String protocolCode() {
        return isGrpc() ? "gRPC" : "WS";
    }
}
