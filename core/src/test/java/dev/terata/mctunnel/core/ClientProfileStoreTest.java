package dev.terata.mctunnel.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ClientProfileStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void roundTripsOrderedProfilesAndSelectedId() throws Exception {
        Path file = temporaryDirectory.resolve("config/client-profiles.properties");
        ClientProfile first = new ClientProfile(UUID.randomUUID(),
            "wss://example.test/a/very/long/tunnel/path", "token:=one", "Primary", 25566);
        ClientProfile second = new ClientProfile(UUID.randomUUID(),
            "ws://127.0.0.1:8081/tunnel", "另一个令牌", "第二个服务器", 25567);
        ClientProfileStore.State expected = new ClientProfileStore.State(
            List.of(first, second), second.id(), second.id());

        ClientProfileStore.save(file, expected);
        ClientProfileStore.LoadResult loaded = ClientProfileStore.load(file, null);

        assertNull(loaded.error());
        assertFalse(loaded.migrated());
        assertEquals(expected.profiles(), loaded.state().profiles());
        assertEquals(second.id(), loaded.state().selectedId());
        assertEquals(second.id(), loaded.state().autoConnectId());
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            assertEquals(List.of(), siblings
                .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .toList());
        }
    }

    @Test
    void createsAndPersistsDefaultProfileWhenNoConfigurationExists() {
        Path profiles = temporaryDirectory.resolve("config/client-profiles.properties");

        ClientProfileStore.LoadResult loaded = ClientProfileStore.load(profiles, null);

        assertNull(loaded.error());
        assertFalse(loaded.migrated());
        assertTrue(Files.exists(profiles));
        assertEquals(25566, loaded.state().profiles().get(0).localPort());
        assertNull(loaded.state().autoConnectId());
    }

    @Test
    void importsLegacyConfigWithoutDeletingOrRewritingIt() throws Exception {
        Path legacy = temporaryDirectory.resolve("client.properties");
        Path profiles = temporaryDirectory.resolve("client-profiles.properties");
        ClientConfig config = new ClientConfig("wss://legacy.example/tunnel", "legacy-token", "Legacy", 25570);
        config.save(legacy);
        String legacyBefore = Files.readString(legacy);

        ClientProfileStore.LoadResult loaded = ClientProfileStore.load(profiles, legacy);

        assertTrue(loaded.migrated());
        assertNull(loaded.error());
        assertTrue(Files.exists(profiles));
        assertEquals(legacyBefore, Files.readString(legacy));
        assertEquals("wss://legacy.example/tunnel", loaded.state().profiles().get(0).gateway());
        assertEquals("legacy-token", loaded.state().profiles().get(0).token());
        assertEquals(25570, loaded.state().profiles().get(0).localPort());
        assertNull(loaded.state().autoConnectId());
    }

    @Test
    void reportsMalformedProfileFileWithoutOverwritingIt() throws Exception {
        Path profiles = temporaryDirectory.resolve("client-profiles.properties");
        String malformed = "schemaVersion=999\nprofiles=broken\n";
        Files.writeString(profiles, malformed);

        ClientProfileStore.LoadResult loaded = ClientProfileStore.load(profiles, null);

        assertNotNull(loaded.error());
        assertEquals(malformed, Files.readString(profiles));
        assertEquals(1, loaded.state().profiles().size());
        assertNull(loaded.state().autoConnectId());
    }

    @Test
    void loadsExistingV2ProfileWithoutAutoConnectProperty() throws Exception {
        Path profiles = temporaryDirectory.resolve("client-profiles.properties");
        ClientProfile profile = ClientProfile.createDefault();
        ClientProfileStore.save(profiles, new ClientProfileStore.State(List.of(profile), profile.id(), null));
        String withoutAutoConnect = Files.readString(profiles).replaceAll("(?m)^autoConnect=.*\\R?", "");
        Files.writeString(profiles, withoutAutoConnect);

        ClientProfileStore.LoadResult loaded = ClientProfileStore.load(profiles, null);

        assertNull(loaded.error());
        assertEquals(profile.id(), loaded.state().selectedId());
        assertNull(loaded.state().autoConnectId());
    }

    @Test
    void ignoresAutoConnectIdThatDoesNotReferenceAProfile() throws Exception {
        Path profiles = temporaryDirectory.resolve("client-profiles.properties");
        ClientProfile profile = ClientProfile.createDefault();
        ClientProfileStore.save(profiles,
            new ClientProfileStore.State(List.of(profile), profile.id(), profile.id()));
        String invalid = Files.readString(profiles)
            .replace("autoConnect=" + profile.id(), "autoConnect=" + UUID.randomUUID());
        Files.writeString(profiles, invalid);

        ClientProfileStore.LoadResult loaded = ClientProfileStore.load(profiles, null);

        assertNull(loaded.error());
        assertNull(loaded.state().autoConnectId());
    }

    @Test
    void switchingAndClearingAutoConnectKeepsAtMostOneProfile() throws Exception {
        Path profiles = temporaryDirectory.resolve("client-profiles.properties");
        ClientProfile first = ClientProfile.createDefault();
        ClientProfile second = ClientProfile.createDefault();

        ClientProfileStore.save(profiles,
            new ClientProfileStore.State(List.of(first, second), first.id(), first.id()));
        ClientProfileStore.save(profiles,
            new ClientProfileStore.State(List.of(first, second), first.id(), second.id()));
        assertEquals(second.id(), ClientProfileStore.load(profiles, null).state().autoConnectId());

        ClientProfileStore.save(profiles,
            new ClientProfileStore.State(List.of(first, second), first.id(), null));
        assertNull(ClientProfileStore.load(profiles, null).state().autoConnectId());
    }
}
