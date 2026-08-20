package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientConfig;
import dev.terata.mctunnel.core.ClientLog;
import dev.terata.mctunnel.core.TunnelClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

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
        ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.client_ready").getString());

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof MultiplayerScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(
                    Text.translatable("button.minecraft_websocket_tunnel.open"),
                    button -> client.setScreen(new TunnelConfigScreen(screen, config, CONFIG_PATH)))
                    .dimensions(Math.max(5, scaledWidth - 110), 8, 100, 20)
                    .build());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(MinecraftTunnelClientMod::observeTunnel);
    }

    private static void observeTunnel(MinecraftClient client) {
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
            case ERROR -> reportError(client, Text.translatable("message.minecraft_websocket_tunnel.tunnel_failed", status));
        }
    }

    public static synchronized TunnelClient toggle(ClientConfig newConfig) throws Exception {
        config = newConfig;
        if (tunnel != null && tunnel.state() != TunnelClient.State.STOPPED) {
            tunnel.stop();
            tunnel = null;
            ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.stopped").getString());
            return null;
        }
        ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.starting", config.gateway).getString());
        TunnelClient next = new TunnelClient(config);
        next.start();
        tunnel = next;
        ClientLog.info(Text.translatable("log.minecraft_websocket_tunnel.running", next.localPort()).getString());
        return next;
    }

    public static void reportError(MinecraftClient client, Text detail) {
        ClientLog.error(detail.getString());
        SystemToast.show(
            client.getToastManager(),
            SystemToast.Type.PACK_LOAD_FAILURE,
            Text.translatable("toast.minecraft_websocket_tunnel.error_title"),
            detail
        );
    }

    public static void logInfo(Text message) {
        ClientLog.info(message.getString());
    }

    public static TunnelClient tunnel() {
        return tunnel;
    }
}
