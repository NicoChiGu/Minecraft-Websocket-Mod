package dev.terata.mctunnel.core;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public final class WebSocketTunnelTransport implements ITunnelTransport {
    private final URI uri;
    private final String token;
    private final Listener listener;
    private volatile WebSocketClient ws;
    private volatile boolean closed;

    public WebSocketTunnelTransport(URI uri, String token, Listener listener) {
        this.uri = uri;
        this.token = token == null ? "" : token;
        this.listener = listener;
    }

    @Override
    public void connect(long timeout, TimeUnit unit) throws Exception {
        closed = false;
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                if (listener != null) listener.onTransportOpen();
            }

            @Override
            public void onMessage(String message) { }

            @Override
            public void onMessage(ByteBuffer bytes) {
                try {
                    Frame frame = FrameCodec.decode(bytes);
                    if (listener != null) listener.onFrameReceived(frame);
                } catch (RuntimeException ignored) { }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (listener != null) listener.onTransportClose(code, reason);
            }

            @Override
            public void onError(Exception ex) {
                if (listener != null) listener.onTransportError(ex);
            }
        };
        client.addHeader("Authorization", "Bearer " + token);
        client.setConnectionLostTimeout(30);
        this.ws = client;

        if (!client.connectBlocking(timeout, unit)) {
            throw new IOException("Gateway connection timeout");
        }
    }

    @Override
    public void send(Frame frame) {
        WebSocketClient client = ws;
        if (client != null && client.isOpen() && !closed) {
            try {
                client.send(FrameCodec.encode(frame));
            } catch (Exception ignored) { }
        }
    }

    @Override
    public boolean isOpen() {
        WebSocketClient client = ws;
        return client != null && client.isOpen() && !closed;
    }

    @Override
    public void close() {
        closed = true;
        WebSocketClient client = ws;
        ws = null;
        if (client == null) return;
        boolean established = client.isOpen() || client.isClosing();
        try { client.close(CloseFrame.GOING_AWAY, "Client stopping"); }
        catch (RuntimeException ignored) { }
        if (!established && !client.isClosed()) forceClose(client);
    }

    @Override
    public boolean closeAndAwait(long timeout, TimeUnit unit) {
        closed = true;
        WebSocketClient client = ws;
        ws = null;
        if (client == null || client.isClosed()) return true;

        boolean established = client.isOpen() || client.isClosing();
        boolean forced = !established;
        try { client.close(CloseFrame.GOING_AWAY, "Client stopping"); }
        catch (RuntimeException ignored) { }
        if (!established) forceClose(client);

        long timeoutNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + timeoutNanos;
        boolean interrupted = false;
        while (!client.isClosed() && System.nanoTime() - deadline < 0) {
            long remainingNanos = deadline - System.nanoTime();
            long sleepMillis = Math.max(1L, Math.min(10L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ignored) {
                interrupted = true;
                break;
            }
        }
        boolean graceful = client.isClosed();
        if (!graceful) {
            forced = true;
            forceClose(client);
        }
        if (interrupted) Thread.currentThread().interrupt();
        return graceful && !forced;
    }

    private static void forceClose(WebSocketClient client) {
        try {
            Socket socket = client.getSocket();
            if (socket != null) socket.close();
        } catch (IOException | RuntimeException ignored) { }
        try { client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "Client stopping"); }
        catch (RuntimeException ignored) { }
    }
}
