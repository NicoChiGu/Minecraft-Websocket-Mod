package dev.terata.mctunnel.core;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TunnelServer extends WebSocketServer {
    private final ListenerConfig listener;
    private final String targetHost;
    private final int targetPort;
    private final Map<WebSocket, Map<Integer, Socket>> sessions = new ConcurrentHashMap<>();

    public TunnelServer(ListenerConfig listener, String targetHost, int targetPort) {
        super(new InetSocketAddress(listener.bindHost(), listener.bindPort()));
        this.listener = listener;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    /** Legacy constructor for backward compatibility with existing callers. */
    public TunnelServer(ServerConfig config) {
        this(config.listeners().isEmpty()
                ? new ListenerConfig(config.mode(), config.bindHost(), config.bindPort(), config.path(), config.token())
                : config.listeners().get(0),
            config.targetHost(), config.targetPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (!authorized(handshake)) {
            conn.close(1008, "Unauthorized");
            return;
        }
        sessions.put(conn, new ConcurrentHashMap<>());
    }

    private boolean authorized(ClientHandshake handshake) {
        try {
            URI uri = URI.create("ws://localhost" + handshake.getResourceDescriptor());
            if (!listener.path().equals(uri.getPath())) return false;
            String authorization = handshake.getFieldValue("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) return false;
            return constantTimeEquals(listener.token(), authorization.substring("Bearer ".length()));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aa = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (aa.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
        return diff == 0;
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        final Frame frame;
        try { frame = FrameCodec.decode(message); }
        catch (RuntimeException e) { conn.close(1003, "Bad frame"); return; }

        Map<Integer, Socket> sockets = sessions.get(conn);
        if (sockets == null) return;
        try {
            switch (frame.type()) {
                case OPEN -> openTarget(conn, sockets, frame.connectionId());
                case DATA -> {
                    Socket socket = sockets.get(frame.connectionId());
                    if (socket != null && !socket.isClosed()) {
                        socket.getOutputStream().write(frame.payload());
                        socket.getOutputStream().flush();
                    }
                }
                case CLOSE -> closeSocket(sockets.remove(frame.connectionId()));
                case PING -> conn.send(FrameCodec.encode(new Frame(FrameType.PONG, 0, frame.payload())));
                default -> { }
            }
        } catch (IOException e) {
            closeSocket(sockets.remove(frame.connectionId()));
            if (conn.isOpen()) conn.send(FrameCodec.encode(Frame.close(frame.connectionId())));
        }
    }

    @Override public void onMessage(WebSocket conn, String message) { conn.close(1003, "Binary frames required"); }

    private void openTarget(WebSocket conn, Map<Integer, Socket> sockets, int id) throws IOException {
        if (sockets.containsKey(id)) return;
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(targetHost, targetPort), 5000);
        sockets.put(id, socket);
        Thread reader = new Thread(() -> pumpTarget(conn, sockets, id, socket), "mc-wss-server-" + id);
        reader.setDaemon(true);
        reader.start();
    }

    private void pumpTarget(WebSocket conn, Map<Integer, Socket> sockets, int id, Socket socket) {
        byte[] buffer = new byte[32 * 1024];
        try {
            int n;
            while ((n = socket.getInputStream().read(buffer)) >= 0) {
                if (n == 0) continue;
                byte[] data = java.util.Arrays.copyOf(buffer, n);
                if (conn.isOpen()) conn.send(FrameCodec.encode(Frame.data(id, data)));
                else break;
            }
        } catch (IOException ignored) {
        } finally {
            sockets.remove(id, socket);
            closeSocket(socket);
            if (conn.isOpen()) conn.send(FrameCodec.encode(Frame.close(id)));
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Map<Integer, Socket> sockets = sessions.remove(conn);
        if (sockets != null) sockets.values().forEach(TunnelServer::closeSocket);
    }

    @Override public void onError(WebSocket conn, Exception ex) { }
    @Override public void onStart() { }

    public void shutdown() {
        try { stop(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
