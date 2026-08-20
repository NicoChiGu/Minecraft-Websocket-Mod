package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class TunnelHud {
    private TunnelHud() { }

    public static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        TunnelClient tunnel = MinecraftTunnelClientMod.tunnel();
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) return;

        boolean connected = tunnel != null && tunnel.state() == TunnelClient.State.RUNNING;
        Component line = connected
            ? Component.translatable("hud.minecraft_websocket_tunnel.connected")
            : Component.translatable("hud.minecraft_websocket_tunnel.disconnected");

        graphics.text(client.font, line, 6, 6, connected ? 0xFF55FF55 : 0xFFFF5555, true);
    }
}
