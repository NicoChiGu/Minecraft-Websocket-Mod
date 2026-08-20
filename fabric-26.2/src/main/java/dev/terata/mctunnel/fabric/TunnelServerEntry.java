package dev.terata.mctunnel.fabric;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;

final class TunnelServerEntry extends ServerSelectionList.OnlineServerEntry {
    private final ServerSelectionList list;
    private final JoinMultiplayerScreen screen;

    TunnelServerEntry(ServerSelectionList list, JoinMultiplayerScreen screen, ServerData serverData) {
        list.super(screen, serverData);
        this.list = list;
        this.screen = screen;
    }

    @Override public void updateServerList() { }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return false;
        list.setSelected(this);
        TunnelMultiplayerBridge.sync(screen);
        if (doubleClick) join();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) {
            list.setSelected(this);
            TunnelMultiplayerBridge.sync(screen);
            join();
            return true;
        }
        return false;
    }
}
