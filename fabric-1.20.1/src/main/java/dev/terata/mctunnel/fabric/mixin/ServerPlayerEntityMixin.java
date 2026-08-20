package dev.terata.mctunnel.fabric.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "getPlayerListName", at = @At("RETURN"), cancellable = true)
    private void minecraftWebsocketTunnel$appendLatency(CallbackInfoReturnable<Text> info) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        Text baseName = info.getReturnValue() == null ? player.getDisplayName() : info.getReturnValue();
        int latency = Math.max(0, player.pingMilliseconds);
        MutableText displayName = Text.empty()
            .append(baseName)
            .append(Text.literal("  " + latency + "ms").formatted(Formatting.GRAY));
        info.setReturnValue(displayName);
    }
}
