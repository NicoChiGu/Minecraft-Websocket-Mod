package dev.terata.mctunnel.core;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TunnelClientLifecycleTest {
    @Test
    void startsAndStopsAgainstLocalGateway() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        WebSocketServer gateway = gateway(started);
        gateway.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));

        ClientConfig config = new ClientConfig("ws://127.0.0.1:" + gateway.getPort(), "token", "test", 0);
        TunnelClient client = new TunnelClient(config);
        try {
            client.start();
            assertEquals(TunnelClient.State.RUNNING, client.state());
            assertTrue(client.localPort() > 0);
        } finally {
            client.stop();
            gateway.stop(1_000);
        }
        assertEquals(TunnelClient.State.STOPPED, client.state());
    }

    @Test
    void stopDoesNotWaitForBlockingWebSocketHandshake() throws Exception {
        try (ServerSocket stalledGateway = new ServerSocket(0)) {
            CountDownLatch accepted = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<?> accepter = executor.submit(() -> {
                try (Socket ignored = stalledGateway.accept()) {
                    accepted.countDown();
                    Thread.sleep(5_000L);
                } catch (Exception ignored) { }
            });
            ClientConfig config = new ClientConfig(
                "ws://127.0.0.1:" + stalledGateway.getLocalPort(), "token", "test", 0);
            TunnelClient client = new TunnelClient(config);
            Future<?> start = executor.submit(() -> {
                try { client.start(); }
                catch (Exception ignored) { }
            });
            assertTrue(accepted.await(3, TimeUnit.SECONDS));

            long before = System.nanoTime();
            boolean graceful = client.stopAndAwait(200, TimeUnit.MILLISECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

            assertTrue(elapsedMillis < 1_000L, "stop blocked for " + elapsedMillis + "ms");
            assertFalse(graceful);
            start.get(3, TimeUnit.SECONDS);
            assertEquals(TunnelClient.State.STOPPED, client.state());
            accepter.cancel(true);
            executor.shutdownNow();
        }
    }

    @Test
    void stopAndAwaitCompletesWebSocketCloseHandshake() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        WebSocketServer gateway = gateway(new InetSocketAddress("127.0.0.1", 0), started, closed);
        gateway.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));

        TunnelClient client = new TunnelClient(new ClientConfig(
            "ws://127.0.0.1:" + gateway.getPort(), "token", "test", 0));
        try {
            client.start();

            assertTrue(client.stopAndAwait(2, TimeUnit.SECONDS));
            assertTrue(closed.await(100, TimeUnit.MILLISECONDS));
            assertEquals(TunnelClient.State.STOPPED, client.state());
        } finally {
            client.stop();
            gateway.stop(1_000);
        }
    }

    @Test
    void initialRetryCanBeCancelledDuringCountdown() throws Exception {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            unusedPort = reservation.getLocalPort();
        }
        CountDownLatch scheduled = new CountDownLatch(1);
        AtomicInteger maxAttempts = new AtomicInteger();
        AtomicInteger delaySeconds = new AtomicInteger();
        TunnelClient client = new TunnelClient(
            new ClientConfig("ws://127.0.0.1:" + unusedPort, "token", "test", 0),
            new TunnelClient.ReconnectListener() {
                @Override public void onReconnectScheduled(int attempt, int maximum, long delay) {
                    maxAttempts.set(maximum);
                    delaySeconds.set((int) delay);
                    scheduled.countDown();
                }
            });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> start = executor.submit(() -> {
            try { client.startWithInitialRetries(); }
            catch (Exception ignored) { }
        });
        try {
            assertTrue(scheduled.await(3, TimeUnit.SECONDS));
            assertEquals(5, maxAttempts.get());
            assertEquals(5, delaySeconds.get());

            client.stop();
            start.get(3, TimeUnit.SECONDS);
            assertEquals(TunnelClient.State.STOPPED, client.state());
        } finally {
            client.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void initialRetryConnectsWhenGatewayBecomesAvailable() throws Exception {
        int gatewayPort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            gatewayPort = reservation.getLocalPort();
        }
        CountDownLatch retryScheduled = new CountDownLatch(1);
        TunnelClient client = new TunnelClient(
            new ClientConfig("ws://127.0.0.1:" + gatewayPort, "token", "test", 0),
            new TunnelClient.ReconnectListener() {
                @Override public void onReconnectScheduled(int attempt, int maximum, long delay) {
                    retryScheduled.countDown();
                }
            });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> start = executor.submit(() -> {
            try { client.startWithInitialRetries(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        WebSocketServer gateway = null;
        try {
            assertTrue(retryScheduled.await(3, TimeUnit.SECONDS));
            CountDownLatch gatewayStarted = new CountDownLatch(1);
            gateway = gateway(new InetSocketAddress("127.0.0.1", gatewayPort), gatewayStarted, null);
            gateway.start();
            assertTrue(gatewayStarted.await(3, TimeUnit.SECONDS));

            start.get(8, TimeUnit.SECONDS);
            assertEquals(TunnelClient.State.RUNNING, client.state());
        } finally {
            client.stopAndAwait(2, TimeUnit.SECONDS);
            if (gateway != null) gateway.stop(1_000);
            executor.shutdownNow();
        }
    }

    @Test
    void canRetryAfterOccupiedLocalPortIsReleased() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        WebSocketServer gateway = gateway(started);
        gateway.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));

        try (ServerSocket occupied = new ServerSocket(0)) {
            ClientConfig config = new ClientConfig(
                "ws://127.0.0.1:" + gateway.getPort(), "token", "test", occupied.getLocalPort());
            TunnelClient client = new TunnelClient(config);

            assertThrows(Exception.class, client::start);
            assertEquals(TunnelClient.State.ERROR, client.state());
            occupied.close();

            client.start();
            assertEquals(TunnelClient.State.RUNNING, client.state());
            client.stop();
        } finally {
            gateway.stop(1_000);
        }
    }

    private static WebSocketServer gateway(CountDownLatch started) {
        return gateway(new InetSocketAddress("127.0.0.1", 0), started, null);
    }

    private static WebSocketServer gateway(
        InetSocketAddress address,
        CountDownLatch started,
        CountDownLatch closed
    ) {
        return new WebSocketServer(address) {
            @Override public void onOpen(WebSocket connection, ClientHandshake handshake) { }
            @Override public void onClose(WebSocket connection, int code, String reason, boolean remote) {
                if (closed != null) closed.countDown();
            }
            @Override public void onMessage(WebSocket connection, String message) { }
            @Override public void onError(WebSocket connection, Exception exception) { }
            @Override public void onStart() { started.countDown(); }
        };
    }
}
