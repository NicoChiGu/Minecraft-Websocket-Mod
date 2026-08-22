package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientConfig;
import dev.terata.mctunnel.core.ClientProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.UUID;

public final class TunnelProfileEditScreen extends Screen {
    private final Screen parent;
    private final UUID profileId;
    private String draftGateway;
    private String draftToken;
    private String draftName;
    private String draftPort;
    private boolean isGrpc;
    private ButtonWidget protocolButton;
    private TextFieldWidget gateway;
    private TextFieldWidget token;
    private TextFieldWidget name;
    private TextFieldWidget localPort;

    public TunnelProfileEditScreen(Screen parent, ClientProfile profile) {
        super(Text.translatable(profile == null
            ? "screen.minecraft_websocket_tunnel.add_profile"
            : "screen.minecraft_websocket_tunnel.edit_profile"));
        this.parent = parent;
        ClientConfig config = profile == null ? new ClientConfig() : profile.toConfig();
        this.profileId = profile == null ? UUID.randomUUID() : profile.id();
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
            protocolButton = addDrawableChild(ButtonWidget.builder(protocolButtonText(), b -> toggleProtocol())
                .dimensions(left, 36, columnWidth, 20).build());
            gateway = field(left + columnWidth + 6, 36, columnWidth, Text.translatable("field.minecraft_websocket_tunnel.gateway"), draftGateway, 2048);
            token = field(left, 76, columnWidth,
                Text.translatable("field.minecraft_websocket_tunnel.token"), draftToken, 4096);
            name = field(left + columnWidth + 6, 76, columnWidth,
                Text.translatable("field.minecraft_websocket_tunnel.display_name"), draftName, 128);
            localPort = field(left, 116, columnWidth,
                Text.translatable("field.minecraft_websocket_tunnel.local_port"), draftPort, 5);
        } else {
            int fieldWidth = Math.min(360, contentWidth);
            left = (width - fieldWidth) / 2;
            int step = Math.max(30, Math.min(34, (height - 80) / 5));
            int y = 34;
            protocolButton = addDrawableChild(ButtonWidget.builder(protocolButtonText(), b -> toggleProtocol())
                .dimensions(left, y, fieldWidth, 20).build());
            y += step;
            gateway = field(left, y, fieldWidth, Text.translatable("field.minecraft_websocket_tunnel.gateway"), draftGateway, 2048);
            y += step;
            token = field(left, y, fieldWidth, Text.translatable("field.minecraft_websocket_tunnel.token"), draftToken, 4096);
            y += step;
            name = field(left, y, fieldWidth, Text.translatable("field.minecraft_websocket_tunnel.display_name"), draftName, 128);
            y += step;
            localPort = field(left, y, fieldWidth, Text.translatable("field.minecraft_websocket_tunnel.local_port"), draftPort, 5);
            contentWidth = fieldWidth;
        }

        int buttonY = height - 36;
        int buttonWidth = (contentWidth - 4) / 2;
        ButtonWidget save = addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.save"), b -> save())
            .dimensions(left, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.cancel"), b -> close())
            .dimensions(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        boolean editable = !MinecraftTunnelClientMod.isLocked();
        protocolButton.active = editable;
        gateway.setEditable(editable);
        token.setEditable(editable);
        name.setEditable(editable);
        localPort.setEditable(editable);
        save.active = editable;
    }

    private Text protocolButtonText() {
        return Text.translatable("field.minecraft_websocket_tunnel.protocol", isGrpc ? "gRPC" : "WebSocket");
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
            gateway.setText(current);
        }
    }

    private TextFieldWidget field(int left, int y, int fieldWidth, Text label, String value, int maxLength) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y, fieldWidth, 20, label);
        field.setMaxLength(maxLength);
        field.setText(value);
        addDrawableChild(field);
        return field;
    }

    private void captureDraft() {
        if (gateway == null) return;
        draftGateway = gateway.getText();
        draftToken = token.getText();
        draftName = name.getText();
        draftPort = localPort.getText();
        isGrpc = draftGateway.startsWith("grpc://") || draftGateway.startsWith("grpcs://");
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        captureDraft();
        super.resize(client, width, height);
    }

    private void save() {
        captureDraft();
        String gatewayValue = draftGateway.trim();
        String nameValue = draftName.trim();
        boolean validWs = gatewayValue.startsWith("ws://") || gatewayValue.startsWith("wss://");
        boolean validGrpc = gatewayValue.startsWith("grpc://") || gatewayValue.startsWith("grpcs://");
        if (!validWs && !validGrpc) {
            fail(Text.translatable("message.minecraft_websocket_tunnel.gateway_invalid"));
            return;
        }
        if (nameValue.isBlank()) {
            fail(Text.translatable("message.minecraft_websocket_tunnel.name_invalid"));
            return;
        }
        int port;
        try {
            port = Integer.parseInt(draftPort.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            fail(Text.translatable("message.minecraft_websocket_tunnel.local_port_invalid"));
            return;
        }
        ClientProfile profile = new ClientProfile(profileId, gatewayValue, draftToken, nameValue, port);
        if (MinecraftTunnelClientMod.upsertProfile(profile)) {
            MinecraftTunnelClientMod.setMessage(Text.translatable("message.minecraft_websocket_tunnel.saved"));
            close();
        }
    }

    private void fail(Text message) {
        MinecraftTunnelClientMod.setMessage(message);
        if (client != null) MinecraftTunnelClientMod.reportError(client, message);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xCC101010);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        if (gateway != null) drawLabel(context, gateway, "field.minecraft_websocket_tunnel.gateway");
        if (token != null) drawLabel(context, token, "field.minecraft_websocket_tunnel.token");
        if (name != null) drawLabel(context, name, "field.minecraft_websocket_tunnel.display_name");
        if (localPort != null) drawLabel(context, localPort, "field.minecraft_websocket_tunnel.local_port");
        Text status = fit(MinecraftTunnelClientMod.statusText().getString(), Math.max(20, width - 20));
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height - 12, 0xFFD060);
    }

    private void drawLabel(DrawContext context, TextFieldWidget field, String key) {
        context.drawTextWithShadow(textRenderer,
            fit(Text.translatable(key).getString(), field.getWidth()), field.getX(), field.getY() - 9, 0xA0A0A0);
    }

    private Text fit(String value, int maxWidth) {
        if (textRenderer.getWidth(value) <= maxWidth) return Text.literal(value);
        String suffix = "...";
        return Text.literal(textRenderer.trimToWidth(value, Math.max(0, maxWidth - textRenderer.getWidth(suffix))) + suffix);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
