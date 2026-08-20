package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientProfile;
import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class TunnelConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private final Screen parent;
    private int page;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listBottom;
    private Button addButton;
    private Button editButton;
    private Button deleteButton;
    private Button connectionButton;
    private Button previousButton;
    private Button nextButton;
    private final List<Button> autoConnectButtons = new ArrayList<>();

    public TunnelConfigScreen(Screen parent) {
        super(Component.translatable("screen.minecraft_websocket_tunnel.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        listWidth = Math.max(140, Math.min(380, width - 24));
        listLeft = (width - listWidth) / 2;
        listTop = 36;
        int actionsTop = Math.max(72, height - 72);
        listBottom = Math.max(listTop + ROW_HEIGHT, actionsTop - 6);
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1))
            .bounds(listLeft + listWidth - 44, 8, 20, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1))
            .bounds(listLeft + listWidth - 20, 8, 20, 20).build());

        autoConnectButtons.clear();
        int autoWidth = autoButtonWidth();
        for (int slot = 0; slot < pageSize(); slot++) {
            int rowSlot = slot;
            Button autoButton = addRenderableWidget(Button.builder(
                    Component.translatable("button.minecraft_websocket_tunnel.enable_auto_connect"),
                    button -> toggleAutoConnect(rowSlot))
                .bounds(listLeft + listWidth - autoWidth - 4,
                    listTop + slot * ROW_HEIGHT + 3, autoWidth, 20)
                .build());
            autoConnectButtons.add(autoButton);
        }

        int gap = 4;
        int topWidth = (listWidth - gap * 2) / 3;
        addButton = addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.add_profile"),
            button -> minecraft.gui.setScreen(new TunnelProfileEditScreen(this, null)))
            .bounds(listLeft, actionsTop, topWidth, 20).build());
        editButton = addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.edit_profile"),
            button -> minecraft.gui.setScreen(new TunnelProfileEditScreen(this, MinecraftTunnelClientMod.selectedProfile())))
            .bounds(listLeft + topWidth + gap, actionsTop, topWidth, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.delete_profile"),
            button -> deleteSelected()).bounds(listLeft + (topWidth + gap) * 2, actionsTop, topWidth, 20).build());

        int bottomY = actionsTop + 24;
        int bottomWidth = (listWidth - gap * 2) / 3;
        connectionButton = addRenderableWidget(Button.builder(connectionButtonText(), button -> toggleConnection())
            .bounds(listLeft, bottomY, bottomWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.logs"),
            button -> minecraft.gui.setScreen(new TunnelLogScreen(this)))
            .bounds(listLeft + bottomWidth + gap, bottomY, bottomWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("button.minecraft_websocket_tunnel.done"), button -> onClose())
            .bounds(listLeft + (bottomWidth + gap) * 2, bottomY, bottomWidth, 20).build());
        updateControls();
    }

    @Override public void tick() { updateControls(); }

    private void updateControls() {
        boolean locked = MinecraftTunnelClientMod.isLocked();
        if (addButton != null) addButton.active = !locked;
        if (editButton != null) editButton.active = !locked;
        if (deleteButton != null) deleteButton.active = !locked && MinecraftTunnelClientMod.profiles().size() > 1;
        if (connectionButton != null) connectionButton.setMessage(connectionButtonText());
        int pages = pageCount();
        if (page >= pages) page = Math.max(0, pages - 1);
        if (previousButton != null) previousButton.active = page > 0;
        if (nextButton != null) nextButton.active = page + 1 < pages;
        List<ClientProfile> profiles = MinecraftTunnelClientMod.profiles();
        int first = page * pageSize();
        for (int slot = 0; slot < autoConnectButtons.size(); slot++) {
            Button button = autoConnectButtons.get(slot);
            int index = first + slot;
            button.visible = index < profiles.size();
            if (!button.visible) continue;
            boolean enabled = profiles.get(index).id().equals(MinecraftTunnelClientMod.autoConnectProfileId());
            button.setMessage(Component.translatable(enabled
                ? "button.minecraft_websocket_tunnel.disable_auto_connect"
                : "button.minecraft_websocket_tunnel.enable_auto_connect"));
        }
    }

    private Component connectionButtonText() {
        TunnelClient active = MinecraftTunnelClientMod.tunnel();
        if (MinecraftTunnelClientMod.isConnecting()) return Component.translatable("button.minecraft_websocket_tunnel.cancel");
        return Component.translatable(active != null && active.state() == TunnelClient.State.RUNNING
            ? "button.minecraft_websocket_tunnel.disconnect" : "button.minecraft_websocket_tunnel.connect");
    }

    private void toggleConnection() {
        if (MinecraftTunnelClientMod.isLocked()) MinecraftTunnelClientMod.stopTunnel();
        else MinecraftTunnelClientMod.startSelected();
        updateControls();
    }

    private void deleteSelected() {
        if (MinecraftTunnelClientMod.deleteSelectedProfile()) {
            MinecraftTunnelClientMod.setMessage(Component.translatable("message.minecraft_websocket_tunnel.profile_deleted"));
        }
        updateControls();
    }

    private void toggleAutoConnect(int rowSlot) {
        int index = page * pageSize() + rowSlot;
        List<ClientProfile> profiles = MinecraftTunnelClientMod.profiles();
        if (index >= 0 && index < profiles.size()) {
            MinecraftTunnelClientMod.toggleAutoConnect(profiles.get(index).id());
        }
        updateControls();
    }

    private int autoButtonWidth() {
        return Math.max(56, Math.min(92, listWidth / 3));
    }

    private int profileTextWidth() {
        return Math.max(20, listWidth - autoButtonWidth() - 18);
    }

    private int pageSize() { return Math.max(1, (listBottom - listTop) / ROW_HEIGHT); }
    private int pageCount() { return Math.max(1, (MinecraftTunnelClientMod.profiles().size() + pageSize() - 1) / pageSize()); }
    private void changePage(int delta) {
        page = Math.max(0, Math.min(pageCount() - 1, page + delta));
        updateControls();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == 0 && event.x() >= listLeft && event.x() < listLeft + listWidth
            && event.y() >= listTop && event.y() < listBottom) {
            int row = ((int) event.y() - listTop) / ROW_HEIGHT;
            int index = page * pageSize() + row;
            List<ClientProfile> profiles = MinecraftTunnelClientMod.profiles();
            if (index >= 0 && index < profiles.size()) {
                MinecraftTunnelClientMod.selectProfile(profiles.get(index).id());
                return true;
            }
        }
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xCC101010);
        List<ClientProfile> profiles = MinecraftTunnelClientMod.profiles();
        ClientProfile selected = MinecraftTunnelClientMod.selectedProfile();
        ClientProfile active = MinecraftTunnelClientMod.activeProfile();
        int first = page * pageSize();
        int end = Math.min(profiles.size(), first + pageSize());
        for (int index = first; index < end; index++) {
            ClientProfile profile = profiles.get(index);
            int y = listTop + (index - first) * ROW_HEIGHT;
            boolean isSelected = profile.id().equals(selected.id());
            graphics.fill(listLeft, y, listLeft + listWidth, y + ROW_HEIGHT - 2,
                isSelected ? 0xAA365E86 : 0x880F0F0F);
            graphics.fill(listLeft, y + ROW_HEIGHT - 3, listLeft + listWidth, y + ROW_HEIGHT - 2, 0xFF3A3A3A);
            int color = active != null && active.id().equals(profile.id()) ? 0xFF55FF55 : 0xFFFFFFFF;
            int textWidth = profileTextWidth();
            graphics.text(font, fit(profile.remoteName(), textWidth), listLeft + 6, y + 4, color, true);
            String address = profile.gateway() + "  |  127.0.0.1:" + profile.localPort();
            graphics.text(font, fit(address, textWidth), listLeft + 6, y + 15, 0xFFA0A0A0, true);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        int statusY = height - 12;
        if (MinecraftTunnelClientMod.isConnecting()) drawProgress(graphics, listLeft, statusY - 10, listWidth);
        graphics.centeredText(font, fit(MinecraftTunnelClientMod.statusText().getString(), Math.max(20, width - 20)),
            width / 2, statusY, 0xFFFFD060);
    }

    private void drawProgress(GuiGraphicsExtractor graphics, int x, int y, int progressWidth) {
        graphics.fill(x, y, x + progressWidth, y + 5, 0xFF202020);
        int segmentCount = Math.max(4, progressWidth / 12);
        int activeSegment = (int) ((System.currentTimeMillis() / 120L) % segmentCount);
        int segmentWidth = Math.max(2, (progressWidth - segmentCount + 1) / segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            int distance = Math.floorMod(i - activeSegment, segmentCount);
            int color = distance == 0 ? 0xFF80FF20 : distance == 1 ? 0xFF50C814 : 0xFF17480C;
            int sx = x + i * (segmentWidth + 1);
            graphics.fill(sx, y + 1, Math.min(x + progressWidth, sx + segmentWidth), y + 4, color);
        }
    }

    private Component fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return Component.literal(value);
        String suffix = "...";
        return Component.literal(font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix))) + suffix);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
