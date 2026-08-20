package dev.terata.mctunnel.fabric;

import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.Util;

/** Ephemeral vanilla-style entry that cannot edit, save, delete, or reorder the real server list. */
final class TunnelServerEntry extends MultiplayerServerListWidget.ServerEntry {
    private final MultiplayerScreen screen;
    private long lastClickTime;

    TunnelServerEntry(MultiplayerServerListWidget widget, MultiplayerScreen screen, ServerInfo server) {
        widget.super(screen, server);
        this.screen = screen;
    }

    @Override
    public void saveFile() {
        // The entry deliberately never participates in servers.dat persistence.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        screen.select(this);
        TunnelMultiplayerBridge.sync(screen);
        long now = Util.getMeasuringTimeMs();
        if (now - lastClickTime < 250L) screen.connect();
        lastClickTime = now;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            screen.select(this);
            TunnelMultiplayerBridge.sync(screen);
            screen.connect();
            return true;
        }
        return false;
    }
}
