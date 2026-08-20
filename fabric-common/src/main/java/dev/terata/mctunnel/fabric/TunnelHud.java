package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public final class TunnelHud {
    private TunnelHud() {}

    public static void render(DrawContext context, float tickDelta) {
        TunnelClient tunnel = MinecraftTunnelClientMod.tunnel();
        if (tunnel == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer == null) return;

        int color = switch (tunnel.state()) {
            case RUNNING -> 0x55FF55;
            case CONNECTING -> 0xFFFF55;
            default -> 0xFF5555;
        };

        String latency = tunnel.pingMs() >= 0 ? tunnel.pingMs() + "ms" : "--ms";
        Text line = Text.literal("Tunnel: " + tunnel.state().name() + " " + latency);
        Text ip = Text.literal("IP: 127.0.0.1:" + tunnel.localPort());

        int y = 6;
        context.drawTextWithShadow(client.textRenderer, line, 6, y, color);
        context.drawTextWithShadow(client.textRenderer, ip, 6, y + 12, 0xFFFFFF);
    }
}
