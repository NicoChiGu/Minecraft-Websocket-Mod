package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/** Global client settings, intentionally separate from individual tunnel profiles. */
public final class TunnelClientSettingsScreen extends Screen {
    private final Screen parent;
    private boolean hudEnabled;
    private boolean checkForUpdates;
    private double opacity;
    private ButtonWidget hudButton;
    private ButtonWidget updateButton;

    public TunnelClientSettingsScreen(Screen parent) {
        super(Text.translatable("screen.minecraft_websocket_tunnel.client_settings"));
        this.parent = parent;
        ClientSettings settings = MinecraftTunnelClientMod.clientSettings();
        hudEnabled = settings.networkHudEnabled();
        checkForUpdates = settings.checkForUpdates();
        opacity = settings.networkHudOpacity();
    }

    @Override
    protected void init() {
        int panelWidth = Math.max(220, Math.min(320, width - 32));
        int left = (width - panelWidth) / 2;
        int top = Math.max(38, height / 2 - 62);
        hudButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudEnabled = !hudEnabled;
            updateMessages();
        }).dimensions(left, top, panelWidth, 20).build());
        addDrawableChild(new OpacitySlider(left, top + 26, panelWidth, opacity));
        updateButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            checkForUpdates = !checkForUpdates;
            updateMessages();
        }).dimensions(left, top + 52, panelWidth, 20).build());

        int gap = 6;
        int buttonWidth = (panelWidth - gap) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.save"), button -> save())
            .dimensions(left, top + 102, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.cancel"), button -> close())
            .dimensions(left + buttonWidth + gap, top + 102, buttonWidth, 20).build());
        updateMessages();
    }

    private void updateMessages() {
        Text enabled = Text.translatable(hudEnabled
            ? "value.minecraft_websocket_tunnel.enabled" : "value.minecraft_websocket_tunnel.disabled");
        Text updates = Text.translatable(checkForUpdates
            ? "value.minecraft_websocket_tunnel.enabled" : "value.minecraft_websocket_tunnel.disabled");
        if (hudButton != null) hudButton.setMessage(
            Text.translatable("option.minecraft_websocket_tunnel.network_hud", enabled));
        if (updateButton != null) updateButton.setMessage(
            Text.translatable("option.minecraft_websocket_tunnel.check_updates", updates));
    }

    private void save() {
        if (MinecraftTunnelClientMod.saveClientSettings(
            new ClientSettings(hudEnabled, (float) opacity, checkForUpdates))) {
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xCC101010);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        Text preview = Text.translatable("label.minecraft_websocket_tunnel.preview");
        int previewRight = width / 2 + 54;
        context.drawTextWithShadow(textRenderer, preview, previewRight - 80 - textRenderer.getWidth(preview),
            Math.max(38, height / 2 - 62) + 82, 0xA0A0A0);
        TunnelHud.drawPreview(context, previewRight, Math.max(38, height / 2 - 62) + 81, opacity);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private final class OpacitySlider extends SliderWidget {
        private OpacitySlider(int x, int y, int width, double initialValue) {
            super(x, y, width, 20, Text.empty(), initialValue);
            applyValue();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("option.minecraft_websocket_tunnel.hud_opacity",
                (int) Math.round(opacity * 100.0)));
        }

        @Override
        protected void applyValue() {
            value = Math.round(value * 20.0) / 20.0;
            opacity = value;
            updateMessage();
        }
    }
}
