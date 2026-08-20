package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientProfile;
import dev.terata.mctunnel.core.TunnelClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Profile picker and connection status screen. Editing is handled by TunnelProfileEditScreen. */
public final class TunnelConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private final Screen parent;
    private int page;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listBottom;
    private ButtonWidget addButton;
    private ButtonWidget editButton;
    private ButtonWidget deleteButton;
    private ButtonWidget connectionButton;
    private ButtonWidget previousButton;
    private ButtonWidget nextButton;
    private final List<ButtonWidget> autoConnectButtons = new ArrayList<>();

    public TunnelConfigScreen(Screen parent) {
        super(Text.translatable("screen.minecraft_websocket_tunnel.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        listWidth = Math.max(140, Math.min(380, width - 24));
        listLeft = (width - listWidth) / 2;
        listTop = 36;
        int actionsTop = Math.max(72, height - 72);
        listBottom = Math.max(listTop + ROW_HEIGHT, actionsTop - 6);

        previousButton = addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> changePage(-1))
            .dimensions(listLeft + listWidth - 44, 8, 20, 20).build());
        nextButton = addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> changePage(1))
            .dimensions(listLeft + listWidth - 20, 8, 20, 20).build());

        autoConnectButtons.clear();
        int autoWidth = autoButtonWidth();
        for (int slot = 0; slot < pageSize(); slot++) {
            int rowSlot = slot;
            ButtonWidget autoButton = addDrawableChild(ButtonWidget.builder(
                    Text.translatable("button.minecraft_websocket_tunnel.enable_auto_connect"),
                    button -> toggleAutoConnect(rowSlot))
                .dimensions(listLeft + listWidth - autoWidth - 4,
                    listTop + slot * ROW_HEIGHT + 3, autoWidth, 20)
                .build());
            autoConnectButtons.add(autoButton);
        }

        int gap = 4;
        int topWidth = (listWidth - gap * 2) / 3;
        addButton = addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.add_profile"),
            button -> client.setScreen(new TunnelProfileEditScreen(this, null)))
            .dimensions(listLeft, actionsTop, topWidth, 20).build());
        editButton = addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.edit_profile"),
            button -> client.setScreen(new TunnelProfileEditScreen(this, MinecraftTunnelClientMod.selectedProfile())))
            .dimensions(listLeft + topWidth + gap, actionsTop, topWidth, 20).build());
        deleteButton = addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.delete_profile"),
            button -> deleteSelected())
            .dimensions(listLeft + (topWidth + gap) * 2, actionsTop, topWidth, 20).build());

        int bottomY = actionsTop + 24;
        int bottomWidth = (listWidth - gap * 2) / 3;
        connectionButton = addDrawableChild(ButtonWidget.builder(connectionButtonText(), button -> toggleConnection())
            .dimensions(listLeft, bottomY, bottomWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.logs"),
            button -> client.setScreen(new TunnelLogScreen(this)))
            .dimensions(listLeft + bottomWidth + gap, bottomY, bottomWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("button.minecraft_websocket_tunnel.done"), button -> close())
            .dimensions(listLeft + (bottomWidth + gap) * 2, bottomY, bottomWidth, 20).build());
        updateControls();
    }

    @Override
    public void tick() {
        updateControls();
    }

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
            ButtonWidget button = autoConnectButtons.get(slot);
            int index = first + slot;
            button.visible = index < profiles.size();
            if (!button.visible) continue;
            boolean enabled = profiles.get(index).id().equals(MinecraftTunnelClientMod.autoConnectProfileId());
            button.setMessage(Text.translatable(enabled
                ? "button.minecraft_websocket_tunnel.disable_auto_connect"
                : "button.minecraft_websocket_tunnel.enable_auto_connect"));
        }
    }

    private Text connectionButtonText() {
        TunnelClient active = MinecraftTunnelClientMod.tunnel();
        if (MinecraftTunnelClientMod.isConnecting()) {
            return Text.translatable("button.minecraft_websocket_tunnel.cancel");
        }
        return Text.translatable(active != null && active.state() == TunnelClient.State.RUNNING
            ? "button.minecraft_websocket_tunnel.disconnect"
            : "button.minecraft_websocket_tunnel.connect");
    }

    private void toggleConnection() {
        if (MinecraftTunnelClientMod.isLocked()) MinecraftTunnelClientMod.stopTunnel();
        else MinecraftTunnelClientMod.startSelected();
        updateControls();
    }

    private void deleteSelected() {
        if (MinecraftTunnelClientMod.deleteSelectedProfile()) {
            MinecraftTunnelClientMod.setMessage(Text.translatable("message.minecraft_websocket_tunnel.profile_deleted"));
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

    private int pageSize() {
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    private int pageCount() {
        return Math.max(1, (MinecraftTunnelClientMod.profiles().size() + pageSize() - 1) / pageSize());
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(pageCount() - 1, page + delta));
        updateControls();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0 && mouseX >= listLeft && mouseX < listLeft + listWidth
            && mouseY >= listTop && mouseY < listBottom) {
            int row = ((int) mouseY - listTop) / ROW_HEIGHT;
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xCC101010);
        List<ClientProfile> profiles = MinecraftTunnelClientMod.profiles();
        ClientProfile selected = MinecraftTunnelClientMod.selectedProfile();
        ClientProfile active = MinecraftTunnelClientMod.activeProfile();
        int first = page * pageSize();
        int end = Math.min(profiles.size(), first + pageSize());
        for (int index = first; index < end; index++) {
            ClientProfile profile = profiles.get(index);
            int y = listTop + (index - first) * ROW_HEIGHT;
            boolean isSelected = profile.id().equals(selected.id());
            context.fill(listLeft, y, listLeft + listWidth, y + ROW_HEIGHT - 2,
                isSelected ? 0xAA365E86 : 0x880F0F0F);
            context.fill(listLeft, y + ROW_HEIGHT - 3, listLeft + listWidth, y + ROW_HEIGHT - 2, 0xFF3A3A3A);
            int color = active != null && active.id().equals(profile.id()) ? 0x55FF55 : 0xFFFFFF;
            int textWidth = profileTextWidth();
            context.drawTextWithShadow(textRenderer, fit(profile.remoteName(), textWidth), listLeft + 6, y + 4, color);
            String address = profile.gateway() + "  |  127.0.0.1:" + profile.localPort();
            context.drawTextWithShadow(textRenderer, fit(address, textWidth), listLeft + 6, y + 15, 0xA0A0A0);
        }

        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        int statusY = height - 12;
        if (MinecraftTunnelClientMod.isConnecting()) drawProgress(context, listLeft, statusY - 10, listWidth);
        Text status = fit(MinecraftTunnelClientMod.statusText().getString(), Math.max(20, width - 20));
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, statusY, 0xFFD060);
    }

    private void drawProgress(DrawContext context, int x, int y, int progressWidth) {
        context.fill(x, y, x + progressWidth, y + 5, 0xFF202020);
        int segmentCount = Math.max(4, progressWidth / 12);
        int activeSegment = (int) ((System.currentTimeMillis() / 120L) % segmentCount);
        int segmentWidth = Math.max(2, (progressWidth - segmentCount + 1) / segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            int distance = Math.floorMod(i - activeSegment, segmentCount);
            int color = distance == 0 ? 0xFF80FF20 : distance == 1 ? 0xFF50C814 : 0xFF17480C;
            int sx = x + i * (segmentWidth + 1);
            context.fill(sx, y + 1, Math.min(x + progressWidth, sx + segmentWidth), y + 4, color);
        }
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
