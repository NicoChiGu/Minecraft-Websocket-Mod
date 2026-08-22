package dev.terata.mctunnel.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ServerConfig(String mode, String bindHost, int bindPort, String targetHost, int targetPort,
                           String token, String path, boolean checkForUpdates,
                           List<ListenerConfig> listeners) {

    private static final Pattern LISTENER_SECTION_PATTERN =
        Pattern.compile("^\\[+listener[s]?\\.(\\d+)\\]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LISTENER_ARRAY_PATTERN =
        Pattern.compile("^\\[\\[listener[s]?\\]\\]$", Pattern.CASE_INSENSITIVE);

    public ServerConfig {
        listeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    // --- Legacy constructors (delegate to canonical) ---

    public ServerConfig(String mode, String bindHost, int bindPort, String targetHost, int targetPort,
                        String token, String path, boolean checkForUpdates) {
        this(mode, bindHost, bindPort, targetHost, targetPort, token, path, checkForUpdates,
            buildLegacyListeners(mode, bindHost, bindPort, path, token));
    }

    public ServerConfig(String bindHost, int bindPort, String targetHost, int targetPort, String token, String path, boolean checkForUpdates) {
        this("websocket", bindHost, bindPort, targetHost, targetPort, token, path, checkForUpdates);
    }

    public ServerConfig(String bindHost, int bindPort, String targetHost, int targetPort, String token, String path) {
        this("websocket", bindHost, bindPort, targetHost, targetPort, token, path, true);
    }

    public static ServerConfig defaults() {
        return new ServerConfig("websocket", "0.0.0.0", 8080, "127.0.0.1", 25565, "change-me", "/tunnel", true);
    }

    public boolean isGrpcMode() {
        return "grpc".equalsIgnoreCase(mode);
    }

    public boolean isWebSocketMode() {
        return "websocket".equalsIgnoreCase(mode) || "ws".equalsIgnoreCase(mode);
    }

    public boolean isBothMode() {
        return "both".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode);
    }

    public static ServerConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, template(defaults()));
            return defaults();
        }
        Map<String, String> values = new HashMap<>();
        String currentSectionPrefix = "";
        int arrayIndex = 0;

        for (String raw : Files.readAllLines(file)) {
            String line = raw.split("#", 2)[0].trim();
            if (line.isEmpty()) continue;

            // Handle TOML headers: [listener.1], [[listener]], [global], etc.
            if (line.startsWith("[")) {
                Matcher arrayMatcher = LISTENER_ARRAY_PATTERN.matcher(line);
                if (arrayMatcher.matches()) {
                    arrayIndex++;
                    currentSectionPrefix = "listener." + arrayIndex + ".";
                    continue;
                }

                Matcher sectionMatcher = LISTENER_SECTION_PATTERN.matcher(line);
                if (sectionMatcher.matches()) {
                    int sectionId = Integer.parseInt(sectionMatcher.group(1));
                    currentSectionPrefix = "listener." + sectionId + ".";
                    continue;
                }

                // Other generic table headers like [server] or [target]
                currentSectionPrefix = "";
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }

            if (!key.startsWith("listener.") && !currentSectionPrefix.isEmpty()) {
                values.put(currentSectionPrefix + key, value);
            } else {
                values.put(key, value);
            }
        }

        ServerConfig d = defaults();
        String mode = values.getOrDefault("mode", d.mode());
        String bindHost = values.getOrDefault("bind-host", d.bindHost());
        int bindPort = parseInt(values.get("bind-port"), d.bindPort());
        String targetHost = values.getOrDefault("target-host", d.targetHost());
        int targetPort = parseInt(values.get("target-port"), d.targetPort());
        String token = values.getOrDefault("token", d.token());
        String path = values.getOrDefault("path", d.path());
        boolean checkUpdates = parseBoolean(values.get("check-for-updates"), d.checkForUpdates());

        List<ListenerConfig> listeners = parseListeners(values);
        if (listeners.isEmpty()) {
            // No explicit listener.* keys — build from legacy flat fields
            listeners = buildLegacyListeners(mode, bindHost, bindPort, path, token);
            if (isBothMode(mode)) {
                System.err.println("[Minecraft Tunnel] WARNING: mode=\"both\" is deprecated. "
                    + "Use explicit listener.1.* / listener.2.* sections instead. "
                    + "Auto-splitting: WS on port " + bindPort + ", gRPC on port " + (bindPort + 1) + ".");
            }
        }

        return new ServerConfig(mode, bindHost, bindPort, targetHost, targetPort, token, path, checkUpdates, listeners);
    }

    /**
     * Parse listener.N.* keys from the flat key-value map.
     * Returns an empty list if no listener.* keys are found.
     */
    static List<ListenerConfig> parseListeners(Map<String, String> values) {
        // Group by listener ID: listener.1.mode -> id=1, field=mode
        TreeMap<Integer, Map<String, String>> grouped = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("listener.")) continue;
            // listener.1.mode -> ["listener", "1", "mode"]
            String[] parts = key.split("\\.", 3);
            if (parts.length != 3) continue;
            int id;
            try { id = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) { continue; }
            grouped.computeIfAbsent(id, k -> new HashMap<>()).put(parts[2], entry.getValue());
        }
        if (grouped.isEmpty()) return List.of();

        List<ListenerConfig> result = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> entry : grouped.entrySet()) {
            Map<String, String> fields = entry.getValue();
            result.add(new ListenerConfig(
                fields.getOrDefault("mode", "websocket"),
                fields.getOrDefault("bind-host", ListenerConfig.DEFAULT_BIND_HOST),
                parseInt(fields.get("bind-port"), ListenerConfig.DEFAULT_PORT),
                fields.getOrDefault("path", ListenerConfig.DEFAULT_PATH),
                fields.getOrDefault("token", "change-me")
            ));
        }
        return result;
    }

    /**
     * Build listener list from legacy flat mode/bind/path/token fields.
     * If mode is "both"/"all", creates two listeners (WS on bindPort, gRPC on bindPort+1).
     */
    private static List<ListenerConfig> buildLegacyListeners(String mode, String bindHost, int bindPort,
                                                              String path, String token) {
        if (isBothMode(mode)) {
            return List.of(
                new ListenerConfig("websocket", bindHost, bindPort, path, token),
                new ListenerConfig("grpc", bindHost, bindPort + 1, path, token)
            );
        }
        return List.of(new ListenerConfig(mode, bindHost, bindPort, path, token));
    }

    private static boolean isBothMode(String mode) {
        return "both".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode);
    }

    /**
     * Validate the configuration. Returns a list of error messages (empty if valid).
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        Map<Integer, String> portUsers = new HashMap<>();
        for (ListenerConfig listener : listeners) {
            String existing = portUsers.put(listener.bindPort(), listener.label());
            if (existing != null) {
                errors.add("Duplicate bind port " + listener.bindPort() + ": " + existing + " and " + listener.label());
            }
        }
        return errors;
    }

    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    public static String template(ServerConfig c) {
        return String.join("\n", List.of(
            "# Minecraft Tunnel server configuration",
            "# Target Minecraft server address (global)",
            "target-host = \"" + c.targetHost() + "\"",
            "target-port = " + c.targetPort(),
            "check-for-updates = " + c.checkForUpdates(),
            "",
            "# Multi-listener setup (supports both WebSocket and gRPC or multiple tokens):",
            "# Listener 1 (WebSocket):",
            "listener.1.mode = \"websocket\"",
            "listener.1.bind-host = \"" + c.bindHost() + "\"",
            "listener.1.bind-port = " + c.bindPort(),
            "listener.1.path = \"" + c.path() + "\"",
            "listener.1.token = \"" + c.token() + "\"",
            "",
            "# Listener 2 (gRPC):",
            "# listener.2.mode = \"grpc\"",
            "# listener.2.bind-host = \"0.0.0.0\"",
            "# listener.2.bind-port = 50051",
            "# listener.2.token = \"grpc-secret-token\"",
            ""
        ));
    }
}
