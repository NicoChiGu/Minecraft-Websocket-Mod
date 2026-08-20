package dev.terata.mctunnel.fabric;

import dev.terata.mctunnel.core.ClientProfile;
import dev.terata.mctunnel.core.TunnelClient;
import dev.terata.mctunnel.fabric.mixin.JoinMultiplayerScreenAccessor;
import dev.terata.mctunnel.fabric.mixin.ServerSelectionListAccessor;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.List;

final class TunnelMultiplayerBridge {
    private static ServerSelectionList currentList;
    private static TunnelServerEntry currentEntry;
    private static String currentKey = "";

    private TunnelMultiplayerBridge() { }

    static void sync(JoinMultiplayerScreen screen) {
        JoinMultiplayerScreenAccessor screenAccess = (JoinMultiplayerScreenAccessor) screen;
        ServerSelectionList list = screenAccess.minecraftWebsocketTunnel$getServerSelectionList();
        if (list == null) return;
        if (list != currentList) {
            currentList = list;
            currentEntry = null;
            currentKey = "";
        }
        TunnelClient tunnel = MinecraftTunnelClientMod.tunnel();
        ClientProfile profile = MinecraftTunnelClientMod.activeProfile();
        boolean visible = tunnel != null && tunnel.state() == TunnelClient.State.RUNNING && profile != null;
        ServerSelectionListAccessor listAccess = (ServerSelectionListAccessor) list;
        List<ServerSelectionList.OnlineServerEntry> entries = listAccess.minecraftWebsocketTunnel$getOnlineServers();
        String nextKey = visible ? profile.id() + ":" + tunnel.localPort() : "";

        if (!visible) {
            if (list.getSelected() instanceof TunnelServerEntry) list.setSelected(null);
            if (entries.removeIf(entry -> entry instanceof TunnelServerEntry)) {
                if (currentEntry != null) currentEntry.close();
                listAccess.minecraftWebsocketTunnel$refreshEntries();
            }
            currentEntry = null;
            currentKey = "";
        } else if (currentEntry == null || !currentKey.equals(nextKey) || !entries.contains(currentEntry)) {
            if (list.getSelected() instanceof TunnelServerEntry) list.setSelected(null);
            if (currentEntry != null) currentEntry.close();
            entries.removeIf(entry -> entry instanceof TunnelServerEntry);
            ServerData data = new ServerData(profile.remoteName(), "127.0.0.1:" + tunnel.localPort(), ServerData.Type.OTHER);
            currentEntry = new TunnelServerEntry(list, screen, data);
            currentKey = nextKey;
            entries.add(0, currentEntry);
            listAccess.minecraftWebsocketTunnel$refreshEntries();
        }

        boolean selected = list.getSelected() instanceof TunnelServerEntry;
        if (selected) {
            screenAccess.minecraftWebsocketTunnel$getSelectButton().setMessage(
                Component.translatable("button.minecraft_websocket_tunnel.quick_connect"));
            screenAccess.minecraftWebsocketTunnel$getEditButton().active = false;
            screenAccess.minecraftWebsocketTunnel$getDeleteButton().active = false;
        } else if (screenAccess.minecraftWebsocketTunnel$getSelectButton().getMessage().getString().equals(
            Component.translatable("button.minecraft_websocket_tunnel.quick_connect").getString())) {
            screenAccess.minecraftWebsocketTunnel$getSelectButton().setMessage(Component.translatable("selectServer.select"));
        }
    }
}
