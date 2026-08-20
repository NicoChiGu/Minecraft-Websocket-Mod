package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public final class TunnelHud {
    private TunnelHud() {}

    public static void render(DrawContext context) {
        TunnelClient tunnel = MinecraftTunnelClientMod.tunnel();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer == null) return;

        boolean connected = tunnel != null && tunnel.state() == TunnelClient.State.RUNNING;
        Text line = connected
            ? Text.translatable("hud.minecraft_websocket_tunnel.connected")
            : Text.translatable("hud.minecraft_websocket_tunnel.disconnected");

        context.drawTextWithShadow(client.textRenderer, line, 6, 6, connected ? 0x55FF55 : 0xFF5555);
    }
}
