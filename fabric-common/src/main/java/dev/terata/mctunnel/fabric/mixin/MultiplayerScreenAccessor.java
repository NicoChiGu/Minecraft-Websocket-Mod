package dev.terata.mctunnel.fabric.mixin;

import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiplayerScreen.class)
public interface MultiplayerScreenAccessor {
    @Accessor("serverListWidget") MultiplayerServerListWidget minecraftWebsocketTunnel$getServerListWidget();
    @Accessor("buttonJoin") ButtonWidget minecraftWebsocketTunnel$getJoinButton();
    @Accessor("buttonEdit") ButtonWidget minecraftWebsocketTunnel$getEditButton();
    @Accessor("buttonDelete") ButtonWidget minecraftWebsocketTunnel$getDeleteButton();
}
