package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientSettings;
import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Renders the compact, data-driven network quality indicator. */
public final class TunnelHud {
    private static final String[] GLOBE = {
        "..#####..", ".#######.", "#########", "#########", "#########",
        "#########", "#########", ".#######.", "..#####.."
    };
    private static final String[] LAND = {
        "...##....", ".###..##.", ".##...#..", "..####...", ".#####.#.",
        "...###.#.", ".##..##..", "..##.....", "...##...."
    };

    private TunnelHud() { }

    public static void render(DrawContext context) {
        TunnelClient tunnel = MinecraftTunnelClientMod.tunnel();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientSettings settings = MinecraftTunnelClientMod.clientSettings();
        if (client.textRenderer == null || client.player == null || client.world == null
            || client.options.hudHidden || !settings.networkHudEnabled()
            || tunnel == null || tunnel.state() != TunnelClient.State.RUNNING) {
            return;
        }
        draw(context, client.getWindow().getScaledWidth() - 6, 6,
            tunnel.pingMs(), tunnel.packetLossPercent(), settings.networkHudOpacity(), System.currentTimeMillis());
    }

    static void drawPreview(DrawContext context, int right, int y, double opacity) {
        draw(context, right, y, 42, 5, opacity, System.currentTimeMillis());
    }

    private static void draw(DrawContext context, int right, int y, long pingMs, int loss, double opacity, long now) {
        MinecraftClient client = MinecraftClient.getInstance();
        int alpha = Math.max(0, Math.min(255, (int) Math.round(opacity * 255.0)));
        if (alpha == 0 || client.textRenderer == null) return;

        String pingText = pingMs < 0 ? "--" : Math.min(pingMs, 9999) + " ms";
        String lossText = loss < 0 ? "--" : Math.min(loss, 100) + "%";
        Text label = Text.literal(pingText + " \u00b7 " + lossText);
        int textWidth = client.textRenderer.getWidth(label);
        int iconX = right - textWidth - 13;
        int color = qualityColor(loss);
        drawGlobe(context, iconX, y, color, alpha, loss, now);
        context.drawTextWithShadow(client.textRenderer, label, right - textWidth, y, argb(alpha, 0xE0E0E0));
    }

    private static void drawGlobe(DrawContext context, int x, int y, int rgb, int alpha, int loss, long now) {
        int dimAlpha = Math.max(1, (int) (alpha * 0.34));
        for (int row = 0; row < 9; row++) {
            for (int column = 0; column < 9; column++) {
                if (GLOBE[row].charAt(column) == '#') {
                    context.fill(x + column, y + row, x + column + 1, y + row + 1, argb(dimAlpha, rgb));
                }
                if (LAND[row].charAt(column) == '#') {
                    context.fill(x + column, y + row, x + column + 1, y + row + 1, argb(alpha, rgb));
                }
            }
        }

        int meridian = new int[] {2, 3, 4, 5, 6}[(int) ((now / 420L) % 5L)];
        for (int row = 1; row < 8; row++) {
            if (GLOBE[row].charAt(meridian) == '#') {
                context.fill(x + meridian, y + row, x + meridian + 1, y + row + 1,
                    argb(Math.max(1, (int) (alpha * 0.72)), rgb));
            }
        }

        if (loss >= 30) {
            double pulse = (Math.sin(now / 180.0) + 1.0) * 0.5;
            int pulseColor = argb(Math.max(1, (int) (alpha * (0.18 + pulse * 0.36))), rgb);
            context.fill(x + 3, y - 1, x + 6, y, pulseColor);
            context.fill(x + 3, y + 9, x + 6, y + 10, pulseColor);
            context.fill(x - 1, y + 3, x, y + 6, pulseColor);
            context.fill(x + 9, y + 3, x + 10, y + 6, pulseColor);
        }
    }

    private static int qualityColor(int loss) {
        if (loss < 0) return 0x8A8A8A;
        if (loss > 50) return 0xFF5555;
        if (loss >= 40) return 0xFF9F43;
        if (loss >= 30) return 0xFFDD55;
        if (loss >= 10) return 0x55FF55;
        return 0x32D6A0;
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
