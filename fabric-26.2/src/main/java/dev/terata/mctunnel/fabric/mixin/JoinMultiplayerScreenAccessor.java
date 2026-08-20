package dev.terata.mctunnel.fabric.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(JoinMultiplayerScreen.class)
public interface JoinMultiplayerScreenAccessor {
    @Accessor("serverSelectionList") ServerSelectionList minecraftWebsocketTunnel$getServerSelectionList();
    @Accessor("selectButton") Button minecraftWebsocketTunnel$getSelectButton();
    @Accessor("editButton") Button minecraftWebsocketTunnel$getEditButton();
    @Accessor("deleteButton") Button minecraftWebsocketTunnel$getDeleteButton();
}
