package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class TunnelLogScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private int offsetFromBottom;

    public TunnelLogScreen(Screen parent) {
        super(Component.translatable("screen.minecraft_websocket_tunnel.logs"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = this.height - 28;
        int gap = 6;
        int widthEach = Math.max(60, Math.min(100, (this.width - 50 - gap * 3) / 4));
        int total = widthEach * 4 + gap * 3;
        int left = (this.width - total) / 2;

        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.older"), b -> scroll(pageSize()))
            .bounds(left, y, widthEach, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.newer"), b -> scroll(-pageSize()))
            .bounds(left + widthEach + gap, y, widthEach, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.clear_logs"), b -> {
            ClientLog.clear();
            this.offsetFromBottom = 0;
        }).bounds(left + (widthEach + gap) * 2, y, widthEach, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.done"), b -> onClose())
            .bounds(left + (widthEach + gap) * 3, y, widthEach, 20).build());
    }

    private int pageSize() {
        return Math.max(5, (this.height - 78) / 12);
    }

    private void scroll(int delta) {
        List<ClientLog.Entry> entries = ClientLog.snapshot();
        int max = Math.max(0, entries.size() - pageSize());
        this.offsetFromBottom = Math.max(0, Math.min(max, this.offsetFromBottom + delta));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        List<ClientLog.Entry> entries = ClientLog.snapshot();
        int page = pageSize();
        int max = Math.max(0, entries.size() - page);
        this.offsetFromBottom = Math.min(this.offsetFromBottom, max);
        int end = Math.max(0, entries.size() - this.offsetFromBottom);
        int start = Math.max(0, end - page);
        int y = 34;
        int maxWidth = Math.max(60, this.width - 28);

        for (int i = start; i < end; i++) {
            ClientLog.Entry entry = entries.get(i);
            String line = "[" + TIME.format(Instant.ofEpochMilli(entry.timestampMillis())) + "] [" + entry.level() + "] " + entry.message();
            line = trim(line, maxWidth);
            int color = switch (entry.level()) {
                case INFO -> 0xFFD0D0D0;
                case WARN -> 0xFFFFD060;
                case ERROR -> 0xFFFF7070;
            };
            graphics.text(this.font, line, 14, y, color, true);
            y += 12;
        }

        if (entries.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("log.minecraft_websocket_tunnel.empty"), this.width / 2, 52, 0xFF909090);
        }
        graphics.text(this.font, Component.translatable("log.minecraft_websocket_tunnel.count", entries.size()), 14, this.height - 43, 0xFF909090, true);
    }

    private String trim(String value, int maxWidth) {
        if (this.font.width(value) <= maxWidth) return value;
        String suffix = "...";
        String result = value;
        while (!result.isEmpty() && this.font.width(result + suffix) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
