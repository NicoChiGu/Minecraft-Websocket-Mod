package dev.terata.mctunnel.core;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TunnelClient {
    public enum State { STOPPED, CONNECTING, RUNNING, ERROR }

    private final ClientConfig config;
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile State state = State.STOPPED;
    private volatile String status = "Stopped";
    private volatile WebSocketClient ws;
    private volatile ServerSocket localServer;
    private volatile long rxBytes;
    private volatile long txBytes;
    private volatile long pingMs = -1;
    private volatile Thread heartbeatThread;

    public TunnelClient(ClientConfig config) { this.config = config; }

    public synchronized void start() throws Exception {
        if (state == State.RUNNING || state == State.CONNECTING) return;
        state = State.CONNECTING;
        status = "Connecting to gateway";
        URI uri = URI.create(config.gateway);
        ws = new WebSocketClient(uri) {
            @Override public void onOpen(ServerHandshake handshake) { status = "Gateway connected"; }
            @Override public void onMessage(String message) { }
            @Override public void onMessage(ByteBuffer bytes) { handleFrame(bytes); }
            @Override public void onClose(int code, String reason, boolean remote) {
                closeAllLocal();
                if (state != State.STOPPED) { state = State.ERROR; status = "Gateway disconnected: " + reason; }
            }
            @Override public void onError(Exception ex) {
                if (state != State.STOPPED) { state = State.ERROR; status = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
            }
        };
        ws.addHeader("Authorization", "Bearer " + config.token);
        ws.setConnectionLostTimeout(30);
        if (!ws.connectBlocking(10, TimeUnit.SECONDS)) {
            state = State.ERROR;
            status = "Gateway connection timeout";
            throw new IOException(status);
        }

        localServer = new ServerSocket();
        localServer.setReuseAddress(true);
        localServer.bind(new InetSocketAddress("127.0.0.1", config.localPort));
        state = State.RUNNING;
        status = "Listening on 127.0.0.1:" + localServer.getLocalPort();
        Thread accept = new Thread(this::acceptLoop, "mc-wss-client-accept");
        accept.setDaemon(true);
        accept.start();
        startHeartbeat();
    }

    private void acceptLoop() {
        while (state == State.RUNNING) {
            try {
                Socket socket = localServer.accept();
                socket.setTcpNoDelay(true);
                int id = nextId();
                sockets.put(id, socket);
                send(Frame.open(id));
                Thread reader = new Thread(() -> pumpLocal(id, socket), "mc-wss-client-" + id);
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                if (state == State.RUNNING) { state = State.ERROR; status = "Local listener failed: " + e.getMessage(); }
            }
        }
    }

    private int nextId() {
        int id;
        do { id = ids.updateAndGet(v -> v == Integer.MAX_VALUE ? 1 : v + 1); } while (sockets.containsKey(id));
        return id;
    }

    private void pumpLocal(int id, Socket socket) {
        byte[] buffer = new byte[32 * 1024];
        try {
            int n;
            while ((n = socket.getInputStream().read(buffer)) >= 0) {
                if (n == 0) continue;
                byte[] data = Arrays.copyOf(buffer, n);
                txBytes += n;
                send(Frame.data(id, data));
            }
        } catch (IOException ignored) {
        } finally {
            sockets.remove(id, socket);
            closeSocket(socket);
            if (ws != null && ws.isOpen()) send(Frame.close(id));
        }
    }

    private void handleFrame(ByteBuffer bytes) {
        final Frame frame;
        try { frame = FrameCodec.decode(bytes); }
        catch (RuntimeException e) { return; }
        try {
            switch (frame.type()) {
                case DATA -> {
                    Socket socket = sockets.get(frame.connectionId());
                    if (socket != null && !socket.isClosed()) {
                        rxBytes += frame.payload().length;
                        socket.getOutputStream().write(frame.payload());
                        socket.getOutputStream().flush();
                    }
                }
                case CLOSE -> closeSocket(sockets.remove(frame.connectionId()));
                case PING -> send(new Frame(FrameType.PONG, 0, frame.payload()));
                case PONG -> {
                    if (frame.payload().length == Long.BYTES) {
                        long sent = ByteBuffer.wrap(frame.payload()).getLong();
                        pingMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent));
                    }
                }
                default -> { }
            }
        } catch (IOException e) {
            closeSocket(sockets.remove(frame.connectionId()));
        }
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (state == State.RUNNING) {
                try {
                    send(Frame.ping(System.nanoTime()));
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "mc-wss-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void send(Frame frame) {
        WebSocketClient client = ws;
        if (client != null && client.isOpen()) client.send(FrameCodec.encode(frame));
    }

    public synchronized void stop() {
        state = State.STOPPED;
        status = "Stopped";
        try { if (localServer != null) localServer.close(); } catch (IOException ignored) { }
        closeAllLocal();
        WebSocketClient client = ws;
        if (client != null) client.close();
        ws = null;
        localServer = null;
    }

    private void closeAllLocal() { sockets.values().forEach(TunnelClient::closeSocket); sockets.clear(); }
    private static void closeSocket(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }

    public State state() { return state; }
    public String status() { return status; }
    public long rxBytes() { return rxBytes; }
    public long txBytes() { return txBytes; }
    public int localPort() { ServerSocket s = localServer; return s == null ? config.localPort : s.getLocalPort(); }
    public long pingMs() { return pingMs; }
}
