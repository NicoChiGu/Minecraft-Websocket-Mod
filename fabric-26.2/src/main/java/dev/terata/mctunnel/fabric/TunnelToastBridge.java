package dev.terata.mctunnel.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class TunnelToastBridge {
    private static final SystemToast.SystemToastId TUNNEL_TOAST = new SystemToast.SystemToastId(60_000L);
    private static final AtomicLong GENERATION = new AtomicLong();

    private TunnelToastBridge() { }

    static void show(Minecraft client, Component title, Component detail, long durationMillis) {
        long generation = GENERATION.incrementAndGet();
        client.execute(() -> {
            SystemToast.addOrUpdate(client.gui.toastManager(), TUNNEL_TOAST, title, detail);
            long adjustedDuration = Math.max(1_000L, Math.round(
                durationMillis * client.gui.toastManager().getNotificationDisplayTimeMultiplier()));
            CompletableFuture.delayedExecutor(adjustedDuration, TimeUnit.MILLISECONDS).execute(() ->
                client.execute(() -> {
                    if (GENERATION.get() == generation) {
                        SystemToast.forceHide(client.gui.toastManager(), TUNNEL_TOAST);
                    }
                }));
        });
    }
}
