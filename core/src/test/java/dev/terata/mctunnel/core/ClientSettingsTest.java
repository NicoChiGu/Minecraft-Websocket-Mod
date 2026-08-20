package dev.terata.mctunnel.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSettingsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsDefaultSettingsWhenMissing() {
        Path file = temporaryDirectory.resolve("config/client-settings.properties");
        ClientSettings.LoadResult result = ClientSettings.load(file);

        assertEquals(ClientSettings.defaults(), result.settings());
        assertNull(result.warning());
        assertTrue(Files.exists(file));
    }

    @Test
    void savesAndLoadsPreferences() throws Exception {
        Path file = temporaryDirectory.resolve("client-settings.properties");
        ClientSettings expected = new ClientSettings(false, 0.4F, false);
        ClientSettings.save(file, expected);

        ClientSettings.LoadResult result = ClientSettings.load(file);
        assertEquals(expected, result.settings());
        assertNull(result.warning());
    }

    @Test
    void invalidValuesFallBackWithoutDiscardingValidValues() throws Exception {
        Path file = temporaryDirectory.resolve("client-settings.properties");
        Files.writeString(file, String.join("\n",
            "schemaVersion=1",
            "networkHud.enabled=false",
            "networkHud.opacity=transparent",
            "updates.checkOnStartup=perhaps"));

        ClientSettings.LoadResult result = ClientSettings.load(file);
        assertFalse(result.settings().networkHudEnabled());
        assertEquals(0.85F, result.settings().networkHudOpacity());
        assertTrue(result.settings().checkForUpdates());
        assertNotNull(result.warning());
    }
}
