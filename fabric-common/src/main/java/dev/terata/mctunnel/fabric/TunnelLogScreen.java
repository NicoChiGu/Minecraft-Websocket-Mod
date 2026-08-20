package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientLog;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class TunnelLogScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private int offsetFromBottom;

    public TunnelLogScreen(Screen parent) {
        super(Text.translatable("screen.minecraft_websocket_tunnel.logs"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = height - 28;
        int gap = 6;
        int widthEach = Math.max(60, Math.min(100, (width - 50 - gap * 3) / 4));
        int total = widthEach * 4 + gap * 3;
        int left = (width - total) / 2;

        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.older"), b -> scroll(pageSize()))
            .dimensions(left, y, widthEach, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.newer"), b -> scroll(-pageSize()))
            .dimensions(left + widthEach + gap, y, widthEach, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.clear_logs"), b -> {
            ClientLog.clear();
            offsetFromBottom = 0;
        }).dimensions(left + (widthEach + gap) * 2, y, widthEach, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.done"), b -> close())
            .dimensions(left + (widthEach + gap) * 3, y, widthEach, 20).build());
    }

    private int pageSize() {
        return Math.max(5, (height - 78) / 12);
    }

    private void scroll(int delta) {
        List<ClientLog.Entry> entries = ClientLog.snapshot();
        int max = Math.max(0, entries.size() - pageSize());
        offsetFromBottom = Math.max(0, Math.min(max, offsetFromBottom + delta));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xE0101010);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);

        List<ClientLog.Entry> entries = ClientLog.snapshot();
        int page = pageSize();
        int max = Math.max(0, entries.size() - page);
        offsetFromBottom = Math.min(offsetFromBottom, max);
        int end = Math.max(0, entries.size() - offsetFromBottom);
        int start = Math.max(0, end - page);
        int y = 34;
        int maxWidth = Math.max(60, width - 28);

        for (int i = start; i < end; i++) {
            ClientLog.Entry entry = entries.get(i);
            String line = "[" + TIME.format(Instant.ofEpochMilli(entry.timestampMillis())) + "] [" + entry.level() + "] " + entry.message();
            line = trim(line, maxWidth);
            int color = switch (entry.level()) {
                case INFO -> 0xD0D0D0;
                case WARN -> 0xFFD060;
                case ERROR -> 0xFF7070;
            };
            context.drawTextWithShadow(textRenderer, line, 14, y, color);
            y += 12;
        }

        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("log.minecraft_websocket_tunnel.empty"), width / 2, 52, 0x909090);
        }
        context.drawTextWithShadow(textRenderer,
            Text.translatable("log.minecraft_websocket_tunnel.count", entries.size()), 14, height - 43, 0x909090);
    }

    private String trim(String value, int maxWidth) {
        if (textRenderer.getWidth(value) <= maxWidth) return value;
        String suffix = "...";
        String result = value;
        while (!result.isEmpty() && textRenderer.getWidth(result + suffix) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
