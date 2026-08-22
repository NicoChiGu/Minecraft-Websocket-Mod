package dev.terata.mctunnel.core;

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
    private static final long HEARTBEAT_INTERVAL_MILLIS = 1_000L;
    private static final long HEARTBEAT_BASE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    private static final long HEARTBEAT_MAX_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final int HEARTBEAT_WINDOW_SIZE = 15;
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
    private volatile ITunnelTransport transport;
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
            ITunnelTransport nextTransport = createTransport(uri);
            transport = nextTransport;
            nextTransport.connect(10, TimeUnit.SECONDS);
            if (stopRequested) throw new IOException("Connection stopped");

            ServerSocket nextLocalServer = new ServerSocket();
            try {
                nextLocalServer.setReuseAddress(true);
                nextLocalServer.bind(new InetSocketAddress("127.0.0.1", config.localPort));
                if (stopRequested) throw new IOException("Connection stopped");
                if (!nextTransport.isOpen()) throw new IOException("Gateway closed during connection setup");
                localServer = nextLocalServer;
            } catch (Exception e) {
                try { nextLocalServer.close(); } catch (IOException ignored) { }
                throw e;
            }
        } finally {
            if (connectThread == caller) connectThread = null;
        }
    }

    private ITunnelTransport createTransport(URI uri) {
        ITunnelTransport.Listener listener = new ITunnelTransport.Listener() {
            @Override
            public void onTransportOpen() {
                status = "Gateway connected";
            }

            @Override
            public void onFrameReceived(Frame frame) {
                handleFrame(frame);
            }

            @Override
            public void onTransportClose(int code, String reason) {
                handleGatewayClose(code, reason);
            }

            @Override
            public void onTransportError(Exception ex) {
                if (!stopRequested && state != State.STOPPED) {
                    status = readableMessage(ex);
                }
            }
        };

        if (config.isGrpc()) {
            return new GrpcTunnelTransport(uri, config.token, listener);
        } else {
            return new WebSocketTunnelTransport(uri, config.token, listener);
        }
    }

    private void startAcceptLoop() {
        ServerSocket listener = localServer;
        if (listener == null) return;
        Thread accept = new Thread(() -> acceptLoop(listener), "mc-client-accept");
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
                Thread reader = new Thread(() -> pumpLocal(id, socket), "mc-client-" + id);
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

    private void handleGatewayClose(int code, String reason) {
        if (stopRequested || state == State.STOPPED) return;
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

        Thread retry = new Thread(this::reconnectLoop, "mc-client-reconnect");
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
            ITunnelTransport t = transport;
            if (t != null && t.isOpen()) send(Frame.close(id));
        }
    }

    private void handleFrame(Frame frame) {
        if (frame == null) return;
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
                    long currentPing = pingMs;
                    long timeoutNanos = currentPing > 0
                        ? Math.min(HEARTBEAT_MAX_TIMEOUT_NANOS, Math.max(HEARTBEAT_BASE_TIMEOUT_NANOS, TimeUnit.MILLISECONDS.toNanos(currentPing * 3)))
                        : HEARTBEAT_BASE_TIMEOUT_NANOS;
                    heartbeatTracker.expire(sent, timeoutNanos);
                    heartbeatTracker.recordSent(sent, sent);
                    send(Frame.ping(sent));
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "mc-heartbeat");
        heartbeat.setDaemon(true);
        heartbeatThread = heartbeat;
        heartbeat.start();
    }

    private void send(Frame frame) {
        ITunnelTransport t = transport;
        if (t != null && t.isOpen()) t.send(frame);
    }

    public void stop() {
        ITunnelTransport t = prepareStop();
        if (t != null) t.close();
    }

    /** Stops the tunnel and waits up to the supplied timeout for transport closure. */
    public boolean stopAndAwait(long timeout, TimeUnit unit) {
        if (timeout < 0) throw new IllegalArgumentException("Timeout cannot be negative");
        if (unit == null) throw new IllegalArgumentException("Time unit is required");
        ITunnelTransport t = prepareStop();
        if (t == null) return true;
        return t.closeAndAwait(timeout, unit);
    }

    private ITunnelTransport prepareStop() {
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
        return detachTransport();
    }

    private void shutdownTransport() {
        closeLocalListener();
        closeAllLocal();
        ITunnelTransport t = detachTransport();
        if (t != null) t.close();
    }

    private ITunnelTransport detachTransport() {
        synchronized (lifecycleLock) {
            ITunnelTransport t = transport;
            transport = null;
            return t;
        }
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
