package dev.terata.mctunnel.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record ServerConfig(String bindHost, int bindPort, String targetHost, int targetPort, String token, String path) {
    public static ServerConfig defaults() {
        return new ServerConfig("0.0.0.0", 8080, "127.0.0.1", 25565, "change-me", "/tunnel");
    }

    public static ServerConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, template(defaults()));
            return defaults();
        }
        Map<String, String> values = new HashMap<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.split("#", 2)[0].trim();
            if (line.isEmpty() || line.startsWith("[")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1);
            values.put(key, value);
        }
        ServerConfig d = defaults();
        return new ServerConfig(
            values.getOrDefault("bind-host", d.bindHost()),
            parseInt(values.get("bind-port"), d.bindPort()),
            values.getOrDefault("target-host", d.targetHost()),
            parseInt(values.get("target-port"), d.targetPort()),
            values.getOrDefault("token", d.token()),
            values.getOrDefault("path", d.path())
        );
    }

    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static String template(ServerConfig c) {
        return String.join("\n", List.of(
            "# Minecraft WebSocket Tunnel server configuration",
            "# Put your CDN/reverse proxy in front of bind-port and expose it as WSS/443.",
            "bind-host = \"" + c.bindHost() + "\"",
            "bind-port = " + c.bindPort(),
            "target-host = \"" + c.targetHost() + "\"",
            "target-port = " + c.targetPort(),
            "path = \"" + c.path() + "\"",
            "token = \"" + c.token() + "\"",
            ""
        ));
    }
}
