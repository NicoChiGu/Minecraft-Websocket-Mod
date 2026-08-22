package dev.terata.mctunnel.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.text.Text;

final class TunnelToastBridge {
    private static final Object TOKEN = new Object();

    private TunnelToastBridge() { }

    static void show(MinecraftClient client, Text title, Text detail, long durationMillis) {
        Text sanitizedDetail = sanitize(detail);
        client.execute(() -> {
            ToastManager manager = client.getToastManager();
            TunnelToast toast = manager.getToast(TunnelToast.class, TOKEN);
            if (toast == null) manager.add(new TunnelToast(client, title, sanitizedDetail, durationMillis));
            else toast.update(title, sanitizedDetail, durationMillis);
        });
    }

    private static Text sanitize(Text text) {
        if (text == null) return Text.empty();
        String str = text.getString();
        String sanitized = dev.terata.mctunnel.core.TunnelClient.sanitizeMessage(str, 90);
        return sanitized.equals(str) ? text : Text.literal(sanitized);
    }

    private static final class TunnelToast implements Toast {
        private final SystemToast delegate;
        private Text title;
        private Text detail;
        private long durationMillis;
        private long changedAt = -1L;
        private boolean changed = true;

        private TunnelToast(MinecraftClient client, Text title, Text detail, long durationMillis) {
            this.delegate = SystemToast.create(client, SystemToast.Type.PACK_LOAD_FAILURE, title, detail);
            update(title, detail, durationMillis);
        }

        private void update(Text title, Text detail, long durationMillis) {
            this.title = title;
            this.detail = detail;
            this.durationMillis = Math.max(1_000L, durationMillis);
            this.changed = true;
        }

        @Override
        public Visibility draw(DrawContext context, ToastManager manager, long time) {
            if (changed) {
                changedAt = time;
                changed = false;
            }
            delegate.setContent(title, detail);
            delegate.draw(context, manager, time);
            double multiplier = manager.getNotificationDisplayTimeMultiplier();
            return time - changedAt >= durationMillis * multiplier ? Visibility.HIDE : Visibility.SHOW;
        }

        @Override public Object getType() { return TOKEN; }
        @Override public int getWidth() { return delegate.getWidth(); }
        @Override public int getHeight() { return delegate.getHeight(); }
    }
}
