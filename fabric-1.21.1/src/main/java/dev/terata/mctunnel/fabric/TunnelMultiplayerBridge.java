package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientProfile;
import dev.terata.mctunnel.core.TunnelClient;
import dev.terata.mctunnel.fabric.mixin.MultiplayerScreenAccessor;
import dev.terata.mctunnel.fabric.mixin.MultiplayerServerListWidgetAccessor;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.List;

final class TunnelMultiplayerBridge {
    private static MultiplayerServerListWidget currentWidget;
    private static TunnelServerEntry currentEntry;
    private static String currentKey = "";

    private TunnelMultiplayerBridge() { }

    static void sync(MultiplayerScreen screen) {
        MultiplayerScreenAccessor screenAccess = (MultiplayerScreenAccessor) screen;
        MultiplayerServerListWidget widget = screenAccess.minecraftWebsocketTunnel$getServerListWidget();
        if (widget == null) return;
        if (widget != currentWidget) {
            currentWidget = widget;
            currentEntry = null;
            currentKey = "";
        }

        TunnelClient tunnel = MinecraftTunnelClientMod.tunnel();
        ClientProfile profile = MinecraftTunnelClientMod.activeProfile();
        boolean visible = tunnel != null && tunnel.state() == TunnelClient.State.RUNNING && profile != null;
        MultiplayerServerListWidgetAccessor listAccess = (MultiplayerServerListWidgetAccessor) widget;
        List<MultiplayerServerListWidget.ServerEntry> entries = listAccess.minecraftWebsocketTunnel$getServers();
        String nextKey = visible ? profile.id() + ":" + tunnel.localPort() : "";

        if (!visible) {
            if (widget.getSelectedOrNull() instanceof TunnelServerEntry) screen.select(null);
            if (entries.removeIf(entry -> entry instanceof TunnelServerEntry)) {
                if (currentEntry != null) currentEntry.close();
                listAccess.minecraftWebsocketTunnel$updateEntries();
            }
            currentEntry = null;
            currentKey = "";
        } else if (currentEntry == null || !currentKey.equals(nextKey) || !entries.contains(currentEntry)) {
            if (widget.getSelectedOrNull() instanceof TunnelServerEntry) screen.select(null);
            if (currentEntry != null) currentEntry.close();
            entries.removeIf(entry -> entry instanceof TunnelServerEntry);
            ServerInfo server = new ServerInfo(profile.remoteName(), "127.0.0.1:" + tunnel.localPort(), ServerInfo.ServerType.OTHER);
            currentEntry = new TunnelServerEntry(widget, screen, server);
            currentKey = nextKey;
            entries.add(0, currentEntry);
            listAccess.minecraftWebsocketTunnel$updateEntries();
        }

        boolean selected = widget.getSelectedOrNull() instanceof TunnelServerEntry;
        if (selected) {
            screenAccess.minecraftWebsocketTunnel$getJoinButton().setMessage(
                Text.translatable("button.minecraft_websocket_tunnel.quick_connect"));
            screenAccess.minecraftWebsocketTunnel$getEditButton().active = false;
            screenAccess.minecraftWebsocketTunnel$getDeleteButton().active = false;
        } else if (screenAccess.minecraftWebsocketTunnel$getJoinButton().getMessage().getString().equals(
            Text.translatable("button.minecraft_websocket_tunnel.quick_connect").getString())) {
            screenAccess.minecraftWebsocketTunnel$getJoinButton().setMessage(Text.translatable("selectServer.select"));
        }
    }
}
