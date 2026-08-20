package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientConfig;
import dev.terata.mctunnel.core.ClientLog;
import dev.terata.mctunnel.core.TunnelClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public final class MinecraftTunnelClientMod implements ClientModInitializer {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("minecraft-websocket")
        .resolve("client.properties");

    private static volatile ClientConfig config;
    private static volatile TunnelClient tunnel;
    private static volatile TunnelClient.State observedState = TunnelClient.State.STOPPED;
    private static volatile String observedStatus = "";

    @Override
    public void onInitializeClient() {
        config = ClientConfig.load(CONFIG_PATH);
        ClientLog.info(Component.translatable("log.minecraft_websocket_tunnel.client_ready").getString());

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof JoinMultiplayerScreen) {
                Screens.getWidgets(screen).add(
                    Button.builder(Component.translatable("button.minecraft_websocket_tunnel.open"), button ->
                            client.gui.setScreen(new TunnelConfigScreen(screen, config, CONFIG_PATH)))
                        .bounds(Math.max(5, screen.width - 110), 8, 100, 20)
                        .build()
                );
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(MinecraftTunnelClientMod::observeTunnel);
    }

    private static void observeTunnel(Minecraft client) {
        TunnelClient active = tunnel;
        TunnelClient.State state = active == null ? TunnelClient.State.STOPPED : active.state();
        String status = active == null ? "" : active.status();
        if (state == observedState && status.equals(observedStatus)) return;

        observedState = state;
        observedStatus = status;
        switch (state) {
            case STOPPED -> ClientLog.info(Component.translatable("log.minecraft_websocket_tunnel.stopped").getString());
            case CONNECTING -> ClientLog.info(Component.translatable("log.minecraft_websocket_tunnel.connecting").getString());
            case RUNNING -> ClientLog.info(Component.translatable("log.minecraft_websocket_tunnel.running", active.localPort()).getString());
            case ERROR -> reportError(client, Component.translatable("message.minecraft_websocket_tunnel.tunnel_failed", status));
        }
    }

    public static synchronized TunnelClient toggle(ClientConfig newConfig) throws Exception {
        config = newConfig;

        if (tunnel != null && tunnel.state() != TunnelClient.State.STOPPED) {
            tunnel.stop();
            tunnel = null;
            ClientLog.info(Component.translatable("log.minecraft_websocket_tunnel.stopped").getString());
            return null;
        }

        ClientLog.info(Component.translatable("log.minecraft_websocket_tunnel.starting", config.gateway).getString());
        TunnelClient next = new TunnelClient(config);
        next.start();
        tunnel = next;
        ClientLog.info(Component.translatable("log.minecraft_websocket_tunnel.running", next.localPort()).getString());
        return next;
    }

    public static void reportError(Minecraft client, Component detail) {
        ClientLog.error(detail.getString());
        client.gui.toastManager().addToast(new SystemToast(
            SystemToast.SystemToastId.PACK_LOAD_FAILURE,
            Component.translatable("toast.minecraft_websocket_tunnel.error_title"),
            detail
        ));
    }

    public static void logInfo(Component message) {
        ClientLog.info(message.getString());
    }

    public static TunnelClient tunnel() {
        return tunnel;
    }
}
