package dev.terata.mctunnel.core;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TunnelClient {
    public enum State { STOPPED, CONNECTING, RUNNING, ERROR }

    public interface ReconnectListener {
        default void onReconnectScheduled(int attempt, int maxAttempts, long delaySeconds) { }
        default void onReconnectCountdown(int attempt, int maxAttempts, long remainingSeconds) { }
        default void onReconnectAttempt(int attempt, int maxAttempts) { }
        default void onReconnectSucceeded(int attempt) { }
        default void onReconnectExhausted(int maxAttempts, String reason) { }
    }

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MILLIS = 5_000L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 5_000L;
    private static final long HEARTBEAT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int HEARTBEAT_WINDOW_SIZE = 20;
    private static final ReconnectListener NOOP_RECONNECT_LISTENER = new ReconnectListener() { };

    private final ClientConfig config;
    private final ReconnectListener reconnectListener;
    private final Object lifecycleLock = new Object();
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final HeartbeatTracker heartbeatTracker = new HeartbeatTracker(HEARTBEAT_WINDOW_SIZE);
    private volatile State state = State.STOPPED;
    private volatile String status = "Stopped";
    private volatile WebSocketClient ws;
    private volatile ServerSocket localServer;
    private volatile long rxBytes;
    private volatile long txBytes;
    private volatile long pingMs = -1;
    private volatile Thread heartbeatThread;
    private volatile Thread reconnectThread;
    private volatile Thread connectThread;
    private volatile boolean stopRequested;

    public TunnelClient(ClientConfig config) {
        this(config, NOOP_RECONNECT_LISTENER);
    }

    public TunnelClient(ClientConfig config, ReconnectListener reconnectListener) {
        this.config = config.copy();
        this.reconnectListener = reconnectListener == null ? NOOP_RECONNECT_LISTENER : reconnectListener;
    }

    public void start() throws Exception {
        synchronized (lifecycleLock) {
            if (state == State.RUNNING || state == State.CONNECTING) return;
            stopRequested = false;
            reconnecting.set(false);
            state = State.CONNECTING;
            status = "Connecting to gateway";
        }
        try {
            establishTransport();
        } catch (Exception e) {
            shutdownTransport();
            synchronized (lifecycleLock) {
                if (stopRequested) {
                    state = State.STOPPED;
                    status = "Stopped";
                } else {
                    state = State.ERROR;
                    status = readableMessage(e);
                }
            }
            throw e;
        }

        synchronized (lifecycleLock) {
            if (stopRequested || localServer == null) {
                shutdownTransport();
                state = State.STOPPED;
                status = "Stopped";
                throw new IOException("Connection stopped");
            }
            state = State.RUNNING;
            status = "Listening on 127.0.0.1:" + localServer.getLocalPort();
        }
        startAcceptLoop();
        startHeartbeat();
    }

    /** Starts once, then performs the normal five reconnect attempts if initial setup fails. */
    public void startWithInitialRetries() throws Exception {
        try {
            start();
            return;
        } catch (Exception initialFailure) {
            if (stopRequested) throw initialFailure;
            if (!reconnecting.compareAndSet(false, true)) throw initialFailure;
            state = State.CONNECTING;
            status = "Initial connection failed: " + readableMessage(initialFailure);
            reconnectThread = Thread.currentThread();
        }
        reconnectLoop();
    }

    private void establishTransport() throws Exception {
        Thread caller = Thread.currentThread();
        connectThread = caller;
        try {
            URI uri = URI.create(config.gateway);
            WebSocketClient nextWebSocket = createWebSocket(uri);
            ws = nextWebSocket;
            if (!nextWebSocket.connectBlocking(10, TimeUnit.SECONDS)) {
                throw new IOException("Gateway connection timeout");
            }
            if (stopRequested) throw new IOException("Connection stopped");

            ServerSocket nextLocalServer = new ServerSocket();
            try {
                nextLocalServer.setReuseAddress(true);
                nextLocalServer.bind(new InetSocketAddress("127.0.0.1", config.localPort));
                if (stopRequested) throw new IOException("Connection stopped");
                if (!nextWebSocket.isOpen()) throw new IOException("Gateway closed during connection setup");
                localServer = nextLocalServer;
            } catch (Exception e) {
                try { nextLocalServer.close(); } catch (IOException ignored) { }
                throw e;
            }
        } finally {
            if (connectThread == caller) connectThread = null;
        }
    }

    private WebSocketClient createWebSocket(URI uri) {
        WebSocketClient client = new WebSocketClient(uri) {
            @Override public void onOpen(ServerHandshake handshake) { status = "Gateway connected"; }
            @Override public void onMessage(String message) { }
            @Override public void onMessage(ByteBuffer bytes) { handleFrame(bytes); }
            @Override public void onClose(int code, String reason, boolean remote) {
                handleGatewayClose(this, code, reason);
            }
            @Override public void onError(Exception ex) {
                if (this == ws && !stopRequested && state != State.STOPPED) status = readableMessage(ex);
            }
        };
        client.addHeader("Authorization", "Bearer " + config.token);
        client.setConnectionLostTimeout(30);
        return client;
    }

    private void startAcceptLoop() {
        ServerSocket listener = localServer;
        if (listener == null) return;
        Thread accept = new Thread(() -> acceptLoop(listener), "mc-wss-client-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void acceptLoop(ServerSocket listener) {
        while (state == State.RUNNING && localServer == listener && !listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                socket.setTcpNoDelay(true);
                int id = nextId();
                sockets.put(id, socket);
                send(Frame.open(id));
                Thread reader = new Thread(() -> pumpLocal(id, socket), "mc-wss-client-" + id);
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                if (state == State.RUNNING && localServer == listener) {
                    state = State.ERROR;
                    status = "Local listener failed: " + readableMessage(e);
                }
            }
        }
    }

    private void handleGatewayClose(WebSocketClient source, int code, String reason) {
        if (source != ws || stopRequested || state == State.STOPPED) return;
        String detail = reason == null || reason.isBlank() ? "code " + code : reason;
        if (state == State.RUNNING) beginReconnect("Gateway disconnected: " + detail);
    }

    private void beginReconnect(String reason) {
        if (stopRequested || !reconnecting.compareAndSet(false, true)) return;
        state = State.CONNECTING;
        status = reason;
        Thread previousHeartbeat = heartbeatThread;
        heartbeatThread = null;
        interrupt(previousHeartbeat);
        pingMs = -1;
        heartbeatTracker.reset();
        closeLocalListener();
        closeAllLocal();

        Thread retry = new Thread(this::reconnectLoop, "mc-wss-client-reconnect");
        retry.setDaemon(true);
        reconnectThread = retry;
        retry.start();
    }

    private void reconnectLoop() {
        String lastError = status;
        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            if (stopRequested) return;
            notifyReconnectScheduled(attempt);
            long delaySeconds = TimeUnit.MILLISECONDS.toSeconds(RECONNECT_DELAY_MILLIS);
            for (long remaining = delaySeconds; remaining > 0; remaining--) {
                notifyReconnectCountdown(attempt, remaining);
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stopRequested) return;
            }

            state = State.CONNECTING;
            status = "Reconnecting to gateway (" + attempt + "/" + MAX_RECONNECT_ATTEMPTS + ")";
            notifyReconnectAttempt(attempt);
            shutdownTransport();
            try {
                establishTransport();
                if (stopRequested) {
                    shutdownTransport();
                    return;
                }
                state = State.RUNNING;
                status = "Listening on 127.0.0.1:" + localServer.getLocalPort();
                reconnecting.set(false);
                reconnectThread = null;
                startAcceptLoop();
                startHeartbeat();
                notifyReconnectSucceeded(attempt);
                return;
            } catch (Exception e) {
                lastError = readableMessage(e);
                status = lastError;
                shutdownTransport();
            }
        }

        if (stopRequested) return;
        shutdownTransport();
        status = "Reconnect attempts exhausted: " + lastError;
        state = State.STOPPED;
        stopRequested = true;
        reconnecting.set(false);
        reconnectThread = null;
        notifyReconnectExhausted(lastError);
    }

    private void notifyReconnectScheduled(int attempt) {
        try {
            reconnectListener.onReconnectScheduled(
                attempt,
                MAX_RECONNECT_ATTEMPTS,
                TimeUnit.MILLISECONDS.toSeconds(RECONNECT_DELAY_MILLIS)
            );
        } catch (RuntimeException ignored) { }
    }

    private void notifyReconnectSucceeded(int attempt) {
        try { reconnectListener.onReconnectSucceeded(attempt); }
        catch (RuntimeException ignored) { }
    }

    private void notifyReconnectCountdown(int attempt, long remainingSeconds) {
        try { reconnectListener.onReconnectCountdown(attempt, MAX_RECONNECT_ATTEMPTS, remainingSeconds); }
        catch (RuntimeException ignored) { }
    }

    private void notifyReconnectAttempt(int attempt) {
        try { reconnectListener.onReconnectAttempt(attempt, MAX_RECONNECT_ATTEMPTS); }
        catch (RuntimeException ignored) { }
    }

    private void notifyReconnectExhausted(String reason) {
        try { reconnectListener.onReconnectExhausted(MAX_RECONNECT_ATTEMPTS, reason); }
        catch (RuntimeException ignored) { }
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
                        long roundTripNanos = heartbeatTracker.recordPong(sent, System.nanoTime());
                        if (roundTripNanos >= 0) {
                            pingMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(roundTripNanos));
                        }
                    }
                }
                default -> { }
            }
        } catch (IOException e) {
            closeSocket(sockets.remove(frame.connectionId()));
        }
    }

    private void startHeartbeat() {
        Thread previousHeartbeat = heartbeatThread;
        heartbeatThread = null;
        interrupt(previousHeartbeat);
        heartbeatTracker.reset();
        pingMs = -1;
        Thread heartbeat = new Thread(() -> {
            while (state == State.RUNNING && heartbeatThread == Thread.currentThread()) {
                try {
                    long sent = System.nanoTime();
                    heartbeatTracker.expire(sent, HEARTBEAT_TIMEOUT_NANOS);
                    heartbeatTracker.recordSent(sent, sent);
                    send(Frame.ping(sent));
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "mc-wss-heartbeat");
        heartbeat.setDaemon(true);
        heartbeatThread = heartbeat;
        heartbeat.start();
    }

    private void send(Frame frame) {
        WebSocketClient client = ws;
        if (client != null && client.isOpen()) client.send(FrameCodec.encode(frame));
    }

    public void stop() {
        WebSocketClient client = prepareStop();
        closeWebSocket(client);
    }

    /** Stops the tunnel and waits up to the supplied timeout for the WebSocket close handshake. */
    public boolean stopAndAwait(long timeout, TimeUnit unit) {
        if (timeout < 0) throw new IllegalArgumentException("Timeout cannot be negative");
        if (unit == null) throw new IllegalArgumentException("Time unit is required");
        WebSocketClient client = prepareStop();
        return closeWebSocketAndAwait(client, timeout, unit);
    }

    private WebSocketClient prepareStop() {
        synchronized (lifecycleLock) {
            stopRequested = true;
            state = State.STOPPED;
            status = "Stopped";
        }
        reconnecting.set(false);
        interrupt(connectThread);
        interrupt(reconnectThread);
        interrupt(heartbeatThread);
        connectThread = null;
        reconnectThread = null;
        heartbeatThread = null;
        heartbeatTracker.reset();
        pingMs = -1;
        closeLocalListener();
        closeAllLocal();
        return detachWebSocket();
    }

    private void shutdownTransport() {
        closeLocalListener();
        closeAllLocal();
        closeWebSocket(detachWebSocket());
    }

    private WebSocketClient detachWebSocket() {
        synchronized (lifecycleLock) {
            WebSocketClient client = ws;
            ws = null;
            return client;
        }
    }

    private static void closeWebSocket(WebSocketClient client) {
        if (client == null) return;
        boolean established = client.isOpen() || client.isClosing();
        try { client.close(CloseFrame.GOING_AWAY, "Client stopping"); }
        catch (RuntimeException ignored) { }
        if (!established && !client.isClosed()) forceCloseWebSocket(client);
    }

    private static boolean closeWebSocketAndAwait(WebSocketClient client, long timeout, TimeUnit unit) {
        if (client == null || client.isClosed()) return true;
        boolean established = client.isOpen() || client.isClosing();
        boolean forced = !established;
        try { client.close(CloseFrame.GOING_AWAY, "Client stopping"); }
        catch (RuntimeException ignored) { }
        if (!established) forceCloseWebSocket(client);

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
            forceCloseWebSocket(client);
        }
        if (interrupted) Thread.currentThread().interrupt();
        return graceful && !forced;
    }

    private static void forceCloseWebSocket(WebSocketClient client) {
        try {
            Socket socket = client.getSocket();
            if (socket != null) socket.close();
        } catch (IOException | RuntimeException ignored) { }
        try { client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "Client stopping"); }
        catch (RuntimeException ignored) { }
    }

    private void closeLocalListener() {
        ServerSocket listener = localServer;
        localServer = null;
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
    }

    private static void interrupt(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void closeAllLocal() { sockets.values().forEach(TunnelClient::closeSocket); sockets.clear(); }
    private static void closeSocket(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }

    public State state() { return state; }
    public String status() { return status; }
    public long rxBytes() { return rxBytes; }
    public long txBytes() { return txBytes; }
    public int localPort() { ServerSocket s = localServer; return s == null ? config.localPort : s.getLocalPort(); }
    public long pingMs() { return pingMs; }
    public int packetLossPercent() { return heartbeatTracker.lossPercent(); }
}
