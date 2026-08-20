package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientConfig;
import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public final class TunnelConfigScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Path configPath;

    private EditBox gateway;
    private EditBox token;
    private EditBox name;
    private EditBox localPort;
    private Component message;

    public TunnelConfigScreen(Screen parent, ClientConfig config, Path configPath) {
        super(Component.translatable("screen.minecraft_websocket_tunnel.title"));
        this.parent = parent;
        this.config = config;
        this.configPath = configPath;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(360, this.width - 40);
        int left = (this.width - fieldWidth) / 2;
        int y = 48;

        this.gateway = new EditBox(this.font, left, y, fieldWidth, 20,
            Component.translatable("field.minecraft_websocket_tunnel.gateway"));
        this.gateway.setValue(this.config.gateway);
        this.addRenderableWidget(this.gateway);
        y += 38;

        this.token = new EditBox(this.font, left, y, fieldWidth, 20,
            Component.translatable("field.minecraft_websocket_tunnel.token"));
        this.token.setValue(this.config.token);
        this.addRenderableWidget(this.token);
        y += 38;

        this.name = new EditBox(this.font, left, y, fieldWidth, 20,
            Component.translatable("field.minecraft_websocket_tunnel.display_name"));
        this.name.setValue(this.config.remoteName);
        this.addRenderableWidget(this.name);
        y += 38;

        this.localPort = new EditBox(this.font, left, y, fieldWidth, 20,
            Component.translatable("field.minecraft_websocket_tunnel.local_port"));
        this.localPort.setValue(Integer.toString(this.config.localPort));
        this.addRenderableWidget(this.localPort);
        y += 38;

        int gap = 6;
        int buttonWidth = Math.max(65, (fieldWidth - gap * 3) / 4);
        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.save"), button -> save())
            .bounds(left, y, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.toggle"), button -> toggle())
            .bounds(left + buttonWidth + gap, y, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.logs"), button -> openLogs())
            .bounds(left + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.done"), button -> onClose())
            .bounds(left + (buttonWidth + gap) * 3, y, buttonWidth, 20).build());
    }

    private boolean applyFields() {
        this.config.gateway = this.gateway.getValue().trim();
        this.config.token = this.token.getValue();
        this.config.remoteName = this.name.getValue().trim();

        if (!this.config.gateway.startsWith("ws://") && !this.config.gateway.startsWith("wss://")) {
            this.message = Component.translatable("message.minecraft_websocket_tunnel.gateway_invalid");
            notifyError(this.message);
            return false;
        }

        try {
            int port = Integer.parseInt(this.localPort.getValue().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            this.config.localPort = port;
            return true;
        } catch (NumberFormatException e) {
            this.message = Component.translatable("message.minecraft_websocket_tunnel.local_port_invalid");
            notifyError(this.message);
            return false;
        }
    }

    private void save() {
        if (!applyFields()) return;
        try {
            this.config.save(this.configPath);
            this.message = Component.translatable("message.minecraft_websocket_tunnel.saved");
            MinecraftTunnelClientMod.logInfo(this.message);
        } catch (Exception e) {
            this.message = Component.translatable("message.minecraft_websocket_tunnel.save_failed", readableMessage(e));
            notifyError(this.message);
        }
    }

    private void toggle() {
        if (!applyFields()) return;
        try {
            this.config.save(this.configPath);
            TunnelClient activeTunnel = MinecraftTunnelClientMod.toggle(this.config);
            this.message = activeTunnel == null
                ? Component.translatable("message.minecraft_websocket_tunnel.tunnel_stopped")
                : Component.translatable("message.minecraft_websocket_tunnel.tunnel_running", activeTunnel.localPort());
        } catch (Exception e) {
            this.message = Component.translatable("message.minecraft_websocket_tunnel.tunnel_failed", readableMessage(e));
            notifyError(this.message);
        }
    }

    private void openLogs() {
        this.minecraft.gui.setScreen(new TunnelLogScreen(this));
    }

    private void notifyError(Component detail) {
        MinecraftTunnelClientMod.reportError(this.minecraft, detail);
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static Component statusText(TunnelClient tunnel) {
        if (tunnel == null) return Component.translatable("status.minecraft_websocket_tunnel.stopped");
        return switch (tunnel.state()) {
            case STOPPED -> Component.translatable("status.minecraft_websocket_tunnel.stopped");
            case CONNECTING -> Component.translatable("status.minecraft_websocket_tunnel.connecting");
            case RUNNING -> Component.translatable("status.minecraft_websocket_tunnel.running", tunnel.localPort());
            case ERROR -> Component.translatable("status.minecraft_websocket_tunnel.error", tunnel.status());
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int fieldWidth = Math.min(360, this.width - 40);
        int left = (this.width - fieldWidth) / 2;

        graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("field.minecraft_websocket_tunnel.gateway"), left, 37, 0xFFA0A0A0, true);
        graphics.text(this.font, Component.translatable("field.minecraft_websocket_tunnel.token"), left, 75, 0xFFA0A0A0, true);
        graphics.text(this.font, Component.translatable("field.minecraft_websocket_tunnel.display_name"), left, 113, 0xFFA0A0A0, true);
        graphics.text(this.font, Component.translatable("field.minecraft_websocket_tunnel.local_port"), left, 151, 0xFFA0A0A0, true);

        TunnelClient activeTunnel = MinecraftTunnelClientMod.tunnel();
        graphics.centeredText(this.font, statusText(activeTunnel), this.width / 2, 230, 0xFFFFFFFF);
        graphics.centeredText(this.font,
            Component.translatable("hint.minecraft_websocket_tunnel.join", this.config.localPort),
            this.width / 2, 246, 0xFFA0FFA0);

        if (this.message != null) graphics.centeredText(this.font, this.message, this.width / 2, 264, 0xFFFFD060);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
