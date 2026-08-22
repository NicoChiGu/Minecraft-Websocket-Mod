package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientConfig;
import dev.terata.mctunnel.core.ClientProfile;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class TunnelProfileEditScreen extends Screen {
    private final Screen parent;
    private final UUID profileId;
    private String draftGateway;
    private String draftToken;
    private String draftName;
    private String draftPort;
    private boolean isGrpc;
    private Button protocolButton;
    private EditBox gateway;
    private EditBox token;
    private EditBox name;
    private EditBox localPort;

    public TunnelProfileEditScreen(Screen parent, ClientProfile profile) {
        super(Component.translatable(profile == null
            ? "screen.minecraft_websocket_tunnel.add_profile" : "screen.minecraft_websocket_tunnel.edit_profile"));
        this.parent = parent;
        ClientConfig config = profile == null ? new ClientConfig() : profile.toConfig();
        profileId = profile == null ? UUID.randomUUID() : profile.id();
        draftGateway = config.gateway;
        draftToken = config.token;
        draftName = config.remoteName;
        draftPort = Integer.toString(config.localPort);
        isGrpc = draftGateway.startsWith("grpc://") || draftGateway.startsWith("grpcs://");
    }

    @Override
    protected void init() {
        int contentWidth = Math.max(140, Math.min(420, width - 28));
        int left = (width - contentWidth) / 2;
        boolean compactColumns = width >= 200 && height < 230;
        if (compactColumns) {
            int columnWidth = (contentWidth - 6) / 2;
            protocolButton = addRenderableWidget(Button.builder(protocolButtonText(), b -> toggleProtocol())
                .bounds(left, 36, columnWidth, 20).build());
            gateway = field(left + columnWidth + 6, 36, columnWidth,
                Component.translatable("field.minecraft_websocket_tunnel.gateway"), draftGateway, 2048);
            token = field(left, 76, columnWidth,
                Component.translatable("field.minecraft_websocket_tunnel.token"), draftToken, 4096);
            name = field(left + columnWidth + 6, 76, columnWidth,
                Component.translatable("field.minecraft_websocket_tunnel.display_name"), draftName, 128);
            localPort = field(left, 116, columnWidth,
                Component.translatable("field.minecraft_websocket_tunnel.local_port"), draftPort, 5);
        } else {
            int fieldWidth = Math.min(360, contentWidth);
            left = (width - fieldWidth) / 2;
            int step = Math.max(30, Math.min(34, (height - 80) / 5));
            int y = 34;
            protocolButton = addRenderableWidget(Button.builder(protocolButtonText(), b -> toggleProtocol())
                .bounds(left, y, fieldWidth, 20).build());
            y += step;
            gateway = field(left, y, fieldWidth,
                Component.translatable("field.minecraft_websocket_tunnel.gateway"), draftGateway, 2048);
            y += step;
            token = field(left, y, fieldWidth,
                Component.translatable("field.minecraft_websocket_tunnel.token"), draftToken, 4096);
            y += step;
            name = field(left, y, fieldWidth,
                Component.translatable("field.minecraft_websocket_tunnel.display_name"), draftName, 128);
            y += step;
            localPort = field(left, y, fieldWidth,
                Component.translatable("field.minecraft_websocket_tunnel.local_port"), draftPort, 5);
            contentWidth = fieldWidth;
        }
        int buttonY = height - 36;
        int buttonWidth = (contentWidth - 4) / 2;
        Button save = addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.save"), b -> save())
            .bounds(left, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.cancel"), b -> onClose())
            .bounds(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        boolean editable = !MinecraftTunnelClientMod.isLocked();
        protocolButton.active = editable;
        gateway.setEditable(editable);
        token.setEditable(editable);
        name.setEditable(editable);
        localPort.setEditable(editable);
        save.active = editable;
    }

    private Component protocolButtonText() {
        return Component.translatable("field.minecraft_websocket_tunnel.protocol", isGrpc ? "gRPC" : "WebSocket");
    }

    private void toggleProtocol() {
        captureDraft();
        isGrpc = !isGrpc;
        if (protocolButton != null) {
            protocolButton.setMessage(protocolButtonText());
        }
        if (gateway != null) {
            String current = draftGateway.trim();
            if (isGrpc) {
                if (current.startsWith("wss://")) {
                    current = "grpcs://" + current.substring("wss://".length());
                } else if (current.startsWith("ws://")) {
                    current = "grpc://" + current.substring("ws://".length());
                } else if (!current.startsWith("grpc://") && !current.startsWith("grpcs://")) {
                    current = "grpcs://127.0.0.1:8080";
                }
            } else {
                if (current.startsWith("grpcs://")) {
                    current = "wss://" + current.substring("grpcs://".length());
                } else if (current.startsWith("grpc://")) {
                    current = "ws://" + current.substring("grpc://".length());
                } else if (!current.startsWith("ws://") && !current.startsWith("wss://")) {
                    current = "ws://127.0.0.1:8080/tunnel";
                }
            }
            draftGateway = current;
            gateway.setValue(current);
        }
    }

    private EditBox field(int left, int y, int fieldWidth, Component label, String value, int maxLength) {
        EditBox field = new EditBox(font, left, y, fieldWidth, 20, label);
        field.setMaxLength(maxLength);
        field.setValue(value);
        addRenderableWidget(field);
        return field;
    }

    private void captureDraft() {
        if (gateway == null) return;
        draftGateway = gateway.getValue();
        draftToken = token.getValue();
        draftName = name.getValue();
        draftPort = localPort.getValue();
        isGrpc = draftGateway.startsWith("grpc://") || draftGateway.startsWith("grpcs://");
    }

    @Override public void resize(int width, int height) {
        captureDraft();
        super.resize(width, height);
    }

    private void save() {
        captureDraft();
        String gatewayValue = draftGateway.trim();
        String nameValue = draftName.trim();
        boolean validWs = gatewayValue.startsWith("ws://") || gatewayValue.startsWith("wss://");
        boolean validGrpc = gatewayValue.startsWith("grpc://") || gatewayValue.startsWith("grpcs://");
        if (!validWs && !validGrpc) {
            fail(Component.translatable("message.minecraft_websocket_tunnel.gateway_invalid"));
            return;
        }
        if (nameValue.isBlank()) {
            fail(Component.translatable("message.minecraft_websocket_tunnel.name_invalid"));
            return;
        }
        int port;
        try {
            port = Integer.parseInt(draftPort.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            fail(Component.translatable("message.minecraft_websocket_tunnel.local_port_invalid"));
            return;
        }
        ClientProfile profile = new ClientProfile(profileId, gatewayValue, draftToken, nameValue, port);
        if (MinecraftTunnelClientMod.upsertProfile(profile)) {
            MinecraftTunnelClientMod.setMessage(Component.translatable("message.minecraft_websocket_tunnel.saved"));
            onClose();
        }
    }

    private void fail(Component message) {
        MinecraftTunnelClientMod.setMessage(message);
        MinecraftTunnelClientMod.reportError(minecraft, message);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xCC101010);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        if (gateway != null) drawLabel(graphics, gateway, "field.minecraft_websocket_tunnel.gateway");
        if (token != null) drawLabel(graphics, token, "field.minecraft_websocket_tunnel.token");
        if (name != null) drawLabel(graphics, name, "field.minecraft_websocket_tunnel.display_name");
        if (localPort != null) drawLabel(graphics, localPort, "field.minecraft_websocket_tunnel.local_port");
        graphics.centeredText(font, fit(MinecraftTunnelClientMod.statusText().getString(), Math.max(20, width - 20)),
            width / 2, height - 12, 0xFFFFD060);
    }

    private void drawLabel(GuiGraphicsExtractor graphics, EditBox field, String key) {
        graphics.text(font, fit(Component.translatable(key).getString(), field.getWidth()),
            field.getX(), field.getY() - 9, 0xFFA0A0A0, true);
    }

    private Component fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return Component.literal(value);
        String suffix = "...";
        return Component.literal(font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix))) + suffix);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
