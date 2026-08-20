package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientLog;
import dev.terata.mctunnel.core.ClientProfile;
import dev.terata.mctunnel.core.ClientProfileStore;
import dev.terata.mctunnel.core.TunnelClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MinecraftTunnelClientMod implements ClientModInitializer {
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("minecraft-websocket");
    private static final Path PROFILES_PATH = CONFIG_DIR.resolve("client-profiles.properties");
    private static final Path LEGACY_CONFIG_PATH = CONFIG_DIR.resolve("client.properties");
    private static final ExecutorService CONNECTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mc-wss-client-connect");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<ClientProfile> PROFILES = new ArrayList<>();

    private static volatile UUID selectedProfileId;
    private static volatile UUID autoConnectProfileId;
    private static volatile ClientProfile activeProfile;
    private static volatile TunnelClient tunnel;
    private static volatile CompletableFuture<Void> connectionTask;
    private static volatile long requestGeneration;
    private static volatile Text lastMessage;
    private static volatile TunnelClient.State observedState = TunnelClient.State.STOPPED;
    private static volatile String observedStatus = "";
    private static volatile MultiplayerScreen controlsScreen;
    private static volatile ButtonWidget settingsButton;
    private static volatile boolean shuttingDown;

    @Override
    public void onInitializeClient() {
        ClientProfileStore.LoadResult loaded = ClientProfileStore.load(PROFILES_PATH, LEGACY_CONFIG_PATH);
        synchronized (MinecraftTunnelClientMod.class) {
            PROFILES.clear();
            PROFILES.addAll(loaded.state().profiles());
            selectedProfileId = loaded.state().selectedId();
            autoConnectProfileId = loaded.state().autoConnectId();
        }
        if (loaded.migrated()) {
            lastMessage = Text.translatable("message.minecraft_websocket_tunnel.profiles_migrated");
        } else if (loaded.error() != null) {
            lastMessage = Text.translatable("message.minecraft_websocket_tunnel.profiles_load_failed", loaded.error());
            ClientLog.error(lastMessage.getString());
        }
        ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.client_ready").getString());

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof MultiplayerScreen multiplayerScreen)) return;
            ButtonWidget nextSettingsButton = ButtonWidget.builder(
                    Text.translatable("button.minecraft_websocket_tunnel.open"),
                    button -> client.setScreen(new TunnelConfigScreen(screen)))
                .dimensions(5, 5, 100, 20)
                .build();
            Screens.getButtons(screen).add(nextSettingsButton);
            controlsScreen = multiplayerScreen;
            settingsButton = nextSettingsButton;
            layoutSettingsButton(client, multiplayerScreen);
            TunnelMultiplayerBridge.sync(multiplayerScreen);
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> startAutoConnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdownClient());
        ClientTickEvents.END_CLIENT_TICK.register(MinecraftTunnelClientMod::observeTunnel);
        HudRenderCallback.EVENT.register((context, tickCounter) -> TunnelHud.render(context));
    }

    private static void observeTunnel(MinecraftClient client) {
        if (client.currentScreen instanceof MultiplayerScreen screen && screen == controlsScreen) {
            layoutSettingsButton(client, screen);
            TunnelMultiplayerBridge.sync(screen);
        }
        TunnelClient active = tunnel;
        TunnelClient.State state = active == null ? TunnelClient.State.STOPPED : active.state();
        String status = active == null ? "" : active.status();
        if (state == observedState && status.equals(observedStatus)) return;
        observedState = state;
        observedStatus = status;
        switch (state) {
            case STOPPED -> ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.stopped").getString());
            case CONNECTING -> ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.connecting").getString());
            case RUNNING -> ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.running", active.localPort()).getString());
            case ERROR -> ClientLog.error(Text.translatable("message.minecraft_websocket_tunnel.tunnel_failed", status).getString());
        }
    }

    private static void layoutSettingsButton(MinecraftClient client, MultiplayerScreen screen) {
        if (screen != controlsScreen || settingsButton == null) return;
        Text longLabel = Text.translatable("button.minecraft_websocket_tunnel.open");
        int titleWidth = client.textRenderer.getWidth(screen.getTitle());
        int available = Math.max(24, (screen.width - titleWidth) / 2 - 12);
        int buttonWidth = Math.min(100, available);
        settingsButton.setWidth(buttonWidth);
        settingsButton.setMessage(buttonWidth >= client.textRenderer.getWidth(longLabel) + 8 ? longLabel : Text.literal("WS"));
        settingsButton.setX(5);
        settingsButton.setY(5);
    }

    public static synchronized List<ClientProfile> profiles() { return List.copyOf(PROFILES); }

    public static synchronized ClientProfile selectedProfile() {
        return PROFILES.stream().filter(profile -> profile.id().equals(selectedProfileId))
            .findFirst().orElse(PROFILES.get(0));
    }

    public static ClientProfile activeProfile() { return activeProfile; }

    public static UUID autoConnectProfileId() { return autoConnectProfileId; }

    public static synchronized boolean toggleAutoConnect(UUID id) {
        if (id == null || PROFILES.stream().noneMatch(profile -> profile.id().equals(id))) return false;
        UUID previous = autoConnectProfileId;
        autoConnectProfileId = id.equals(previous) ? null : id;
        if (persistProfiles()) return true;
        autoConnectProfileId = previous;
        return false;
    }

    public static synchronized boolean selectProfile(UUID id) {
        if (PROFILES.stream().noneMatch(profile -> profile.id().equals(id))) return false;
        UUID previousSelection = selectedProfileId;
        selectedProfileId = id;
        if (persistProfiles()) return true;
        selectedProfileId = previousSelection;
        return false;
    }

    public static synchronized boolean upsertProfile(ClientProfile profile) {
        if (isLocked()) return false;
        List<ClientProfile> previousProfiles = List.copyOf(PROFILES);
        UUID previousSelection = selectedProfileId;
        int index = -1;
        for (int i = 0; i < PROFILES.size(); i++) {
            if (PROFILES.get(i).id().equals(profile.id())) { index = i; break; }
        }
        if (index >= 0) PROFILES.set(index, profile); else PROFILES.add(profile);
        selectedProfileId = profile.id();
        if (persistProfiles()) return true;
        PROFILES.clear();
        PROFILES.addAll(previousProfiles);
        selectedProfileId = previousSelection;
        return false;
    }

    public static synchronized boolean deleteSelectedProfile() {
        if (isLocked() || PROFILES.size() <= 1) return false;
        List<ClientProfile> previousProfiles = List.copyOf(PROFILES);
        UUID previousSelection = selectedProfileId;
        UUID previousAutoConnect = autoConnectProfileId;
        UUID id = selectedProfile().id();
        PROFILES.removeIf(profile -> profile.id().equals(id));
        selectedProfileId = PROFILES.get(0).id();
        if (id.equals(autoConnectProfileId)) autoConnectProfileId = null;
        if (persistProfiles()) return true;
        PROFILES.clear();
        PROFILES.addAll(previousProfiles);
        selectedProfileId = previousSelection;
        autoConnectProfileId = previousAutoConnect;
        return false;
    }

    private static boolean persistProfiles() {
        try {
            ClientProfileStore.save(PROFILES_PATH,
                new ClientProfileStore.State(PROFILES, selectedProfileId, autoConnectProfileId));
            return true;
        } catch (Exception e) {
            lastMessage = Text.translatable("message.minecraft_websocket_tunnel.save_failed", readableMessage(e));
            reportError(MinecraftClient.getInstance(), lastMessage);
            return false;
        }
    }

    public static synchronized void startSelected() {
        startProfile(selectedProfile(), false);
    }

    private static synchronized void startAutoConnect() {
        if (shuttingDown || autoConnectProfileId == null || isLocked()) return;
        PROFILES.stream()
            .filter(profile -> profile.id().equals(autoConnectProfileId))
            .findFirst()
            .ifPresent(profile -> startProfile(profile, true));
    }

    private static synchronized void startProfile(ClientProfile profile, boolean retryInitialFailure) {
        if (shuttingDown || isLocked()) return;
        TunnelClient previous = tunnel;
        if (previous != null) previous.stop();
        long generation = ++requestGeneration;
        TunnelClient next = createTunnel(profile);
        activeProfile = profile;
        tunnel = next;
        lastMessage = null;
        ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.starting", profile.gateway()).getString());
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                if (retryInitialFailure) next.startWithInitialRetries();
                else next.start();
            }
            catch (Exception e) { throw new CompletionException(e); }
        }, CONNECTOR);
        connectionTask = task;
        task.whenComplete((ignored, error) -> {
            if (shuttingDown || generation != requestGeneration) return;
            MinecraftClient client = MinecraftClient.getInstance();
            executeOnClient(client, () -> finishStart(generation, next, error));
        });
    }

    private static synchronized void finishStart(long generation, TunnelClient started, Throwable error) {
        if (shuttingDown || generation != requestGeneration || tunnel != started) return;
        connectionTask = null;
        if (error == null && started.state() == TunnelClient.State.RUNNING) {
            lastMessage = Text.translatable("message.minecraft_websocket_tunnel.tunnel_running", started.localPort());
            showToast(Text.translatable("screen.minecraft_websocket_tunnel.title"), lastMessage, 3_000L);
            return;
        }
        if (started.state() == TunnelClient.State.STOPPED) return;
        String detail = readableMessage(unwrap(error));
        tunnel = null;
        activeProfile = null;
        lastMessage = Text.translatable("message.minecraft_websocket_tunnel.tunnel_failed", detail);
        reportError(MinecraftClient.getInstance(), lastMessage);
    }

    public static synchronized void stopTunnel() {
        if (shuttingDown) return;
        ++requestGeneration;
        TunnelClient active = tunnel;
        tunnel = null;
        activeProfile = null;
        connectionTask = null;
        if (active != null) active.stop();
        lastMessage = Text.translatable("message.minecraft_websocket_tunnel.tunnel_stopped");
        ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.stopped").getString());
    }

    private static void shutdownClient() {
        TunnelClient active;
        synchronized (MinecraftTunnelClientMod.class) {
            if (shuttingDown) return;
            shuttingDown = true;
            ++requestGeneration;
            active = tunnel;
            tunnel = null;
            activeProfile = null;
            connectionTask = null;
        }
        if (active != null) active.stopAndAwait(2, TimeUnit.SECONDS);
        CONNECTOR.shutdownNow();
    }

    private static TunnelClient createTunnel(ClientProfile profile) {
        return new TunnelClient(profile.toConfig(), new TunnelClient.ReconnectListener() {
            @Override public void onReconnectScheduled(int attempt, int maxAttempts, long delaySeconds) {
                if (shuttingDown) return;
                ClientLog.warn(Text.translatable("toast.minecraft_websocket_tunnel.reconnect_attempt",
                    delaySeconds, attempt, maxAttempts).getString());
            }
            @Override public void onReconnectCountdown(int attempt, int maxAttempts, long remainingSeconds) {
                if (shuttingDown) return;
                showToast(Text.translatable("toast.minecraft_websocket_tunnel.reconnect_title"),
                    Text.translatable("toast.minecraft_websocket_tunnel.reconnect_attempt",
                        remainingSeconds, attempt, maxAttempts), 8_000L);
            }
            @Override public void onReconnectAttempt(int attempt, int maxAttempts) {
                if (shuttingDown) return;
                showToast(Text.translatable("toast.minecraft_websocket_tunnel.reconnect_title"),
                    Text.translatable("toast.minecraft_websocket_tunnel.reconnect_connecting", attempt, maxAttempts), 12_000L);
            }
            @Override public void onReconnectSucceeded(int attempt) {
                if (shuttingDown) return;
                Text detail = Text.translatable("toast.minecraft_websocket_tunnel.reconnect_success", attempt);
                ClientLog.info(detail.getString());
                showToast(Text.translatable("toast.minecraft_websocket_tunnel.reconnect_title"), detail, 3_000L);
            }
            @Override public void onReconnectExhausted(int maxAttempts, String reason) {
                if (shuttingDown) return;
                Text detail = Text.translatable("toast.minecraft_websocket_tunnel.reconnect_exhausted", maxAttempts);
                ClientLog.error(detail.getString() + ": " + reason);
                MinecraftClient client = MinecraftClient.getInstance();
                executeOnClient(client, () -> clearExhaustedTunnel(reason));
                showToast(Text.translatable("toast.minecraft_websocket_tunnel.reconnect_title"), detail, 8_000L);
            }
        });
    }

    private static synchronized void clearExhaustedTunnel(String reason) {
        if (!shuttingDown && tunnel != null && tunnel.state() == TunnelClient.State.STOPPED) {
            tunnel = null;
            activeProfile = null;
            connectionTask = null;
            lastMessage = Text.translatable("message.minecraft_websocket_tunnel.tunnel_failed", reason);
        }
    }

    public static boolean isLocked() {
        TunnelClient active = tunnel;
        return connectionTask != null || (active != null &&
            (active.state() == TunnelClient.State.CONNECTING || active.state() == TunnelClient.State.RUNNING));
    }

    public static boolean isConnecting() {
        TunnelClient active = tunnel;
        return connectionTask != null || (active != null && active.state() == TunnelClient.State.CONNECTING);
    }

    public static Text statusText() {
        TunnelClient active = tunnel;
        if (active != null && active.state() == TunnelClient.State.CONNECTING) {
            return Text.translatable("status.minecraft_websocket_tunnel.connecting");
        }
        Text message = lastMessage;
        if (message != null) return message;
        if (active == null) return Text.translatable("status.minecraft_websocket_tunnel.stopped");
        return switch (active.state()) {
            case STOPPED -> Text.translatable("status.minecraft_websocket_tunnel.stopped");
            case CONNECTING -> Text.translatable("status.minecraft_websocket_tunnel.connecting");
            case RUNNING -> Text.translatable("status.minecraft_websocket_tunnel.running", active.localPort());
            case ERROR -> Text.translatable("status.minecraft_websocket_tunnel.error", active.status());
        };
    }

    public static void setMessage(Text message) {
        lastMessage = message;
        if (message != null) ClientLog.info(message.getString());
    }

    private static void showToast(Text title, Text detail, long durationMillis) {
        if (shuttingDown) return;
        MinecraftClient client = MinecraftClient.getInstance();
        executeOnClient(client, () -> TunnelToastBridge.show(client, title, detail, durationMillis));
    }

    private static void executeOnClient(MinecraftClient client, Runnable action) {
        if (client == null || shuttingDown) return;
        try {
            client.execute(() -> {
                if (!shuttingDown) action.run();
            });
        } catch (RuntimeException e) {
            if (!shuttingDown) ClientLog.error("Failed to schedule tunnel client callback: " + readableMessage(e));
        }
    }

    public static void reportError(MinecraftClient client, Text detail) {
        if (client == null || shuttingDown) return;
        ClientLog.error(detail.getString());
        showToast(Text.translatable("toast.minecraft_websocket_tunnel.error_title"), detail, 8_000L);
    }

    public static void logInfo(Text message) { ClientLog.info(message.getString()); }
    public static TunnelClient tunnel() { return tunnel; }

    private static Throwable unwrap(Throwable error) {
        if (error == null) return new IllegalStateException("Connection failed");
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String readableMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName() : error.getMessage();
    }
}
