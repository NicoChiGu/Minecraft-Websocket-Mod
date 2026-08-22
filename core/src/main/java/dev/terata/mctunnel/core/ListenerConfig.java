package dev.terata.mctunnel.core;

/**
 * Configuration for a single tunnel listener endpoint.
 * Each listener independently specifies its protocol, bind address, path, and authentication token.
 */
public record ListenerConfig(
    String mode,
    String bindHost,
    int bindPort,
    String path,
    String token
) {
    /** Default bind host when none is specified. */
    public static final String DEFAULT_BIND_HOST = "0.0.0.0";
    /** Default WebSocket handshake path. */
    public static final String DEFAULT_PATH = "/tunnel";
    /** Default bind port. */
    public static final int DEFAULT_PORT = 8080;

    public ListenerConfig {
        if (mode == null || mode.isBlank()) mode = "websocket";
        if (bindHost == null || bindHost.isBlank()) bindHost = DEFAULT_BIND_HOST;
        if (path == null || path.isBlank()) path = DEFAULT_PATH;
        if (token == null) token = "";
    }

    public boolean isGrpc() {
        return "grpc".equalsIgnoreCase(mode);
    }

    public boolean isWebSocket() {
        return "websocket".equalsIgnoreCase(mode) || "ws".equalsIgnoreCase(mode);
    }

    /** Human-readable label for log messages, e.g. "WS@:8080" or "gRPC@:50051". */
    public String label() {
        String proto = isGrpc() ? "gRPC" : "WS";
        return proto + "@" + bindHost + ":" + bindPort;
    }
}
