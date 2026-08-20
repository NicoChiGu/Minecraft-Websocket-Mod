package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientConfig;
import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;

public final class TunnelConfigScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Path configPath;
    private TextFieldWidget gateway;
    private TextFieldWidget token;
    private TextFieldWidget name;
    private TextFieldWidget localPort;
    private Text message;

    public TunnelConfigScreen(Screen parent, ClientConfig config, Path configPath) {
        super(Text.translatable("screen.minecraft_websocket_tunnel.title"));
        this.parent = parent;
        this.config = config;
        this.configPath = configPath;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(360, width - 40);
        int left = (width - fieldWidth) / 2;
        int y = 48;

        gateway = new TextFieldWidget(textRenderer, left, y, fieldWidth, 20,
            Text.translatable("field.minecraft_websocket_tunnel.gateway"));
        gateway.setText(config.gateway);
        addDrawableChild(gateway);
        y += 38;

        token = new TextFieldWidget(textRenderer, left, y, fieldWidth, 20,
            Text.translatable("field.minecraft_websocket_tunnel.token"));
        token.setText(config.token);
        addDrawableChild(token);
        y += 38;

        name = new TextFieldWidget(textRenderer, left, y, fieldWidth, 20,
            Text.translatable("field.minecraft_websocket_tunnel.display_name"));
        name.setText(config.remoteName);
        addDrawableChild(name);
        y += 38;

        localPort = new TextFieldWidget(textRenderer, left, y, fieldWidth, 20,
            Text.translatable("field.minecraft_websocket_tunnel.local_port"));
        localPort.setText(Integer.toString(config.localPort));
        addDrawableChild(localPort);
        y += 38;

        int gap = 6;
        int buttonWidth = Math.max(65, (fieldWidth - gap * 3) / 4);
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.save"), b -> save())
            .dimensions(left, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.toggle"), b -> toggle())
            .dimensions(left + buttonWidth + gap, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.logs"), b -> openLogs())
            .dimensions(left + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.done"), b -> close())
            .dimensions(left + (buttonWidth + gap) * 3, y, buttonWidth, 20).build());
    }

    private boolean applyFields() {
        config.gateway = gateway.getText().trim();
        config.token = token.getText();
        config.remoteName = name.getText().trim();

        if (!config.gateway.startsWith("ws://") && !config.gateway.startsWith("wss://")) {
            message = Text.translatable("message.minecraft_websocket_tunnel.gateway_invalid");
            notifyError(message);
            return false;
        }

        try {
            int port = Integer.parseInt(localPort.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            config.localPort = port;
            return true;
        } catch (NumberFormatException e) {
            message = Text.translatable("message.minecraft_websocket_tunnel.local_port_invalid");
            notifyError(message);
            return false;
        }
    }

    private void save() {
        if (!applyFields()) return;
        try {
            config.save(configPath);
            message = Text.translatable("message.minecraft_websocket_tunnel.saved");
            MinecraftTunnelClientMod.logInfo(message);
        } catch (Exception e) {
            message = Text.translatable("message.minecraft_websocket_tunnel.save_failed", readableMessage(e));
            notifyError(message);
        }
    }

    private void toggle() {
        if (!applyFields()) return;
        try {
            config.save(configPath);
            TunnelClient tunnel = MinecraftTunnelClientMod.toggle(config);
            message = tunnel == null
                ? Text.translatable("message.minecraft_websocket_tunnel.tunnel_stopped")
                : Text.translatable("message.minecraft_websocket_tunnel.tunnel_running", tunnel.localPort());
        } catch (Exception e) {
            message = Text.translatable("message.minecraft_websocket_tunnel.tunnel_failed", readableMessage(e));
            notifyError(message);
        }
    }

    private void openLogs() {
        if (client != null) client.setScreen(new TunnelLogScreen(this));
    }

    private void notifyError(Text detail) {
        if (client != null) MinecraftTunnelClientMod.reportError(client, detail);
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static Text statusText(TunnelClient tunnel) {
        if (tunnel == null) return Text.translatable("status.minecraft_websocket_tunnel.stopped");
        return switch (tunnel.state()) {
            case STOPPED -> Text.translatable("status.minecraft_websocket_tunnel.stopped");
            case CONNECTING -> Text.translatable("status.minecraft_websocket_tunnel.connecting");
            case RUNNING -> Text.translatable("status.minecraft_websocket_tunnel.running", tunnel.localPort());
            case ERROR -> Text.translatable("status.minecraft_websocket_tunnel.error", tunnel.status());
        };
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xCC101010);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        int fieldWidth = Math.min(360, width - 40);
        int left = (width - fieldWidth) / 2;
        context.drawTextWithShadow(textRenderer, Text.translatable("field.minecraft_websocket_tunnel.gateway"), left, 37, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.translatable("field.minecraft_websocket_tunnel.token"), left, 75, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.translatable("field.minecraft_websocket_tunnel.display_name"), left, 113, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Text.translatable("field.minecraft_websocket_tunnel.local_port"), left, 151, 0xA0A0A0);

        TunnelClient tunnel = MinecraftTunnelClientMod.tunnel();
        context.drawCenteredTextWithShadow(textRenderer, statusText(tunnel), width / 2, 230, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.translatable("hint.minecraft_websocket_tunnel.join", config.localPort), width / 2, 246, 0xA0FFA0);
        if (message != null) context.drawCenteredTextWithShadow(textRenderer, message, width / 2, 264, 0xFFD060);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
