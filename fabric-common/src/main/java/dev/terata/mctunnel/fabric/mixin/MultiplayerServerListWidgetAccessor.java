package dev.terata.mctunnel.fabric.mixin;

import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(MultiplayerServerListWidget.class)
public interface MultiplayerServerListWidgetAccessor {
    @Accessor("servers") List<MultiplayerServerListWidget.ServerEntry> minecraftWebsocketTunnel$getServers();
    @Invoker("updateEntries") void minecraftWebsocketTunnel$updateEntries();
}
