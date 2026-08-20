package dev.terata.mctunnel.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Versioned, atomic storage for the client's ordered tunnel profiles. */
public final class ClientProfileStore {
    private static final String SCHEMA_VERSION = "2";

    public record State(List<ClientProfile> profiles, UUID selectedId, UUID autoConnectId) {
        public State {
            profiles = List.copyOf(profiles);
            if (profiles.isEmpty()) throw new IllegalArgumentException("At least one profile is required");
            UUID requestedSelection = selectedId;
            if (requestedSelection == null || profiles.stream().noneMatch(profile -> profile.id().equals(requestedSelection))) {
                selectedId = profiles.get(0).id();
            }
            UUID requestedAutoConnect = autoConnectId;
            if (requestedAutoConnect != null
                && profiles.stream().noneMatch(profile -> profile.id().equals(requestedAutoConnect))) {
                autoConnectId = null;
            }
        }

        public State(List<ClientProfile> profiles, UUID selectedId) {
            this(profiles, selectedId, null);
        }
    }

    public record LoadResult(State state, boolean migrated, String error) { }

    private ClientProfileStore() { }

    public static LoadResult load(Path profilesFile, Path legacyFile) {
        if (Files.exists(profilesFile)) {
            try {
                return new LoadResult(readProfiles(profilesFile), false, null);
            } catch (Exception e) {
                ClientProfile fallback = ClientProfile.createDefault();
                return new LoadResult(
                    new State(List.of(fallback), fallback.id(), null),
                    false,
                    readableMessage(e)
                );
            }
        }

        if (legacyFile != null && Files.exists(legacyFile)) {
            ClientConfig legacy = ClientConfig.load(legacyFile);
            ClientProfile imported = ClientProfile.fromConfig(UUID.randomUUID(), legacy);
            State state = new State(List.of(imported), imported.id(), null);
            try {
                save(profilesFile, state);
                return new LoadResult(state, true, null);
            } catch (IOException e) {
                return new LoadResult(state, false, readableMessage(e));
            }
        }

        ClientProfile created = ClientProfile.createDefault();
        State state = new State(List.of(created), created.id(), null);
        try {
            save(profilesFile, state);
            return new LoadResult(state, false, null);
        } catch (IOException e) {
            return new LoadResult(state, false, readableMessage(e));
        }
    }

    public static void save(Path profilesFile, State state) throws IOException {
        Path parent = profilesFile.getParent();
        if (parent != null) Files.createDirectories(parent);

        Properties properties = new Properties();
        properties.setProperty("schemaVersion", SCHEMA_VERSION);
        properties.setProperty("selected", state.selectedId().toString());
        if (state.autoConnectId() != null) {
            properties.setProperty("autoConnect", state.autoConnectId().toString());
        }
        properties.setProperty("profiles", state.profiles().stream()
            .map(profile -> profile.id().toString())
            .reduce((left, right) -> left + "," + right)
            .orElse(""));

        for (ClientProfile profile : state.profiles()) {
            String prefix = "profile." + profile.id() + ".";
            properties.setProperty(prefix + "gateway", profile.gateway());
            properties.setProperty(prefix + "token", profile.token());
            properties.setProperty(prefix + "remoteName", profile.remoteName());
            properties.setProperty(prefix + "localPort", Integer.toString(profile.localPort()));
        }

        String prefix = profilesFile.getFileName() == null ? "client-profiles" : profilesFile.getFileName().toString();
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Minecraft WebSocket Tunnel client profiles");
            }
            try {
                Files.move(temporary, profilesFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, profilesFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static State readProfiles(Path file) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            properties.load(input);
        }
        if (!SCHEMA_VERSION.equals(properties.getProperty("schemaVersion"))) {
            throw new IOException("Unsupported profile schema");
        }

        List<ClientProfile> profiles = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (String rawId : properties.getProperty("profiles", "").split(",")) {
            if (rawId.isBlank()) continue;
            UUID id;
            try { id = UUID.fromString(rawId.trim()); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!seen.add(id)) continue;

            String prefix = "profile." + id + ".";
            String gateway = properties.getProperty(prefix + "gateway", "").trim();
            String token = properties.getProperty(prefix + "token", "");
            String remoteName = properties.getProperty(prefix + "remoteName", "Minecraft Server").trim();
            int localPort;
            try { localPort = Integer.parseInt(properties.getProperty(prefix + "localPort", "25566")); }
            catch (NumberFormatException ignored) { continue; }
            if (gateway.isBlank() || localPort < 1 || localPort > 65535) continue;
            profiles.add(new ClientProfile(id, gateway, token, remoteName, localPort));
        }
        if (profiles.isEmpty()) throw new IOException("Profile file does not contain any valid profiles");

        UUID selected = null;
        try { selected = UUID.fromString(properties.getProperty("selected", "")); }
        catch (IllegalArgumentException ignored) { }
        UUID autoConnect = null;
        try { autoConnect = UUID.fromString(properties.getProperty("autoConnect", "")); }
        catch (IllegalArgumentException ignored) { }
        return new State(profiles, selected, autoConnect);
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
