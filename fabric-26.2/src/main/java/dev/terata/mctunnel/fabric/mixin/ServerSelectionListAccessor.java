package dev.terata.mctunnel.fabric.mixin;

import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ServerSelectionList.class)
public interface ServerSelectionListAccessor {
    @Accessor("onlineServers") List<ServerSelectionList.OnlineServerEntry> minecraftWebsocketTunnel$getOnlineServers();
    @Invoker("refreshEntries") void minecraftWebsocketTunnel$refreshEntries();
}
