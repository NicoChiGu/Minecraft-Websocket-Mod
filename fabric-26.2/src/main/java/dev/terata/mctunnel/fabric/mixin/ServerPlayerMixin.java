package dev.terata.mctunnel.fabric.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void minecraftWebsocketTunnel$appendLatency(CallbackInfoReturnable<Component> info) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        Component baseName = info.getReturnValue() == null ? player.getDisplayName() : info.getReturnValue();
        int latency = Math.max(0, player.connection.latency());
        MutableComponent displayName = Component.empty()
            .append(baseName)
            .append(Component.literal("  " + latency + "ms").withStyle(ChatFormatting.GRAY));
        info.setReturnValue(displayName);
    }
}
