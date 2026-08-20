package dev.terata.mctunnel.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Global client preferences kept separate from the ordered tunnel profiles. */
public record ClientSettings(boolean networkHudEnabled, float networkHudOpacity, boolean checkForUpdates) {
    private static final String SCHEMA_VERSION = "1";

    public record LoadResult(ClientSettings settings, String warning) { }

    public ClientSettings {
        if (!Float.isFinite(networkHudOpacity) || networkHudOpacity < 0.0F || networkHudOpacity > 1.0F) {
            throw new IllegalArgumentException("HUD opacity must be between 0 and 1");
        }
    }

    public static ClientSettings defaults() {
        return new ClientSettings(true, 0.85F, true);
    }

    public static LoadResult load(Path file) {
        if (!Files.exists(file)) {
            ClientSettings defaults = defaults();
            try {
                save(file, defaults);
                return new LoadResult(defaults, null);
            } catch (IOException e) {
                return new LoadResult(defaults, "Could not create client settings: " + readableMessage(e));
            }
        }

        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException e) {
            return new LoadResult(defaults(), "Could not read client settings: " + readableMessage(e));
        }

        ClientSettings defaults = defaults();
        List<String> warnings = new ArrayList<>();
        String schema = properties.getProperty("schemaVersion", SCHEMA_VERSION).trim();
        if (!SCHEMA_VERSION.equals(schema)) warnings.add("unsupported schema " + schema);

        boolean hudEnabled = parseBoolean(properties.getProperty("networkHud.enabled"),
            defaults.networkHudEnabled(), "networkHud.enabled", warnings);
        float opacity = parseOpacity(properties.getProperty("networkHud.opacity"),
            defaults.networkHudOpacity(), warnings);
        boolean updateCheck = parseBoolean(properties.getProperty("updates.checkOnStartup"),
            defaults.checkForUpdates(), "updates.checkOnStartup", warnings);
        return new LoadResult(new ClientSettings(hudEnabled, opacity, updateCheck),
            warnings.isEmpty() ? null : String.join("; ", warnings));
    }

    public static void save(Path file, ClientSettings settings) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("Client settings path has no parent directory");
        Files.createDirectories(parent);

        Properties properties = new Properties();
        properties.setProperty("schemaVersion", SCHEMA_VERSION);
        properties.setProperty("networkHud.enabled", Boolean.toString(settings.networkHudEnabled()));
        properties.setProperty("networkHud.opacity",
            String.format(Locale.ROOT, "%.2f", settings.networkHudOpacity()));
        properties.setProperty("updates.checkOnStartup", Boolean.toString(settings.checkForUpdates()));

        String prefix = file.getFileName() == null ? "client-settings" : file.getFileName().toString();
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Minecraft WebSocket Tunnel client settings");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback, String key, List<String> warnings) {
        if (raw == null) return fallback;
        if ("true".equalsIgnoreCase(raw.trim())) return true;
        if ("false".equalsIgnoreCase(raw.trim())) return false;
        warnings.add(key + " is invalid; using " + fallback);
        return fallback;
    }

    private static float parseOpacity(String raw, float fallback, List<String> warnings) {
        if (raw == null) return fallback;
        try {
            float value = Float.parseFloat(raw.trim());
            if (Float.isFinite(value) && value >= 0.0F && value <= 1.0F) return value;
        } catch (NumberFormatException ignored) { }
        warnings.add("networkHud.opacity is invalid; using " + fallback);
        return fallback;
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName() : e.getMessage();
    }
}
