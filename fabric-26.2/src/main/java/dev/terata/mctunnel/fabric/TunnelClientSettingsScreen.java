package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Global client settings, intentionally separate from individual tunnel profiles. */
public final class TunnelClientSettingsScreen extends Screen {
    private final Screen parent;
    private boolean hudEnabled;
    private boolean checkForUpdates;
    private double opacity;
    private Button hudButton;
    private Button updateButton;

    public TunnelClientSettingsScreen(Screen parent) {
        super(Component.translatable("screen.minecraft_websocket_tunnel.client_settings"));
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
        hudButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudEnabled = !hudEnabled;
            updateMessages();
        }).bounds(left, top, panelWidth, 20).build());
        addRenderableWidget(new OpacitySlider(left, top + 26, panelWidth, opacity));
        updateButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            checkForUpdates = !checkForUpdates;
            updateMessages();
        }).bounds(left, top + 52, panelWidth, 20).build());
        int gap = 6;
        int buttonWidth = (panelWidth - gap) / 2;
        addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.save"), button -> save())
            .bounds(left, top + 102, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.cancel"), button -> onClose())
            .bounds(left + buttonWidth + gap, top + 102, buttonWidth, 20).build());
        updateMessages();
    }

    private void updateMessages() {
        Component enabled = Component.translatable(hudEnabled
            ? "value.minecraft_websocket_tunnel.enabled" : "value.minecraft_websocket_tunnel.disabled");
        Component updates = Component.translatable(checkForUpdates
            ? "value.minecraft_websocket_tunnel.enabled" : "value.minecraft_websocket_tunnel.disabled");
        if (hudButton != null) hudButton.setMessage(
            Component.translatable("option.minecraft_websocket_tunnel.network_hud", enabled));
        if (updateButton != null) updateButton.setMessage(
            Component.translatable("option.minecraft_websocket_tunnel.check_updates", updates));
    }

    private void save() {
        if (MinecraftTunnelClientMod.saveClientSettings(
            new ClientSettings(hudEnabled, (float) opacity, checkForUpdates))) {
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xCC101010);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        int top = Math.max(38, height / 2 - 62);
        Component preview = Component.translatable("label.minecraft_websocket_tunnel.preview");
        int previewRight = width / 2 + 54;
        graphics.text(font, preview, previewRight - 80 - font.width(preview), top + 82, 0xFFA0A0A0, true);
        TunnelHud.drawPreview(graphics, previewRight, top + 81, opacity);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private final class OpacitySlider extends AbstractSliderButton {
        private OpacitySlider(int x, int y, int width, double initialValue) {
            super(x, y, width, 20, Component.empty(), initialValue);
            applyValue();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("option.minecraft_websocket_tunnel.hud_opacity",
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
