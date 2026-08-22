package dev.terata.mctunnel.core;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MultiListenerIntegrationTest {

    @Test
    void simultaneousWebSocketAndGrpcListenersWithDistinctTokens() throws Exception {
        int targetPort;
        try (ServerSocket res = new ServerSocket(0)) { targetPort = res.getLocalPort(); }

        int wsPort;
        try (ServerSocket res = new ServerSocket(0)) { wsPort = res.getLocalPort(); }

        int grpcPort;
        try (ServerSocket res = new ServerSocket(0)) { grpcPort = res.getLocalPort(); }

        // Start mock Minecraft echo server
        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch mockServerReady = new CountDownLatch(1);
        AtomicInteger echoCount = new AtomicInteger();

        ServerSocket mockMcServer = new ServerSocket(targetPort);
        executor.submit(() -> {
            mockServerReady.countDown();
            while (!mockMcServer.isClosed()) {
                try {
                    Socket socket = mockMcServer.accept();
                    executor.submit(() -> {
                        try (socket) {
                            InputStream in = socket.getInputStream();
                            OutputStream out = socket.getOutputStream();
                            byte[] buf = new byte[1024];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                echoCount.incrementAndGet();
                                String req = new String(buf, 0, n, StandardCharsets.UTF_8);
                                out.write(("echo:" + req).getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        });

        assertTrue(mockServerReady.await(3, TimeUnit.SECONDS));

        // Start WebSocket listener on wsPort with token "ws-secret" and path "/ws-tunnel"
        ListenerConfig wsListener = new ListenerConfig("websocket", "127.0.0.1", wsPort, "/ws-tunnel", "ws-secret");
        TunnelServer wsServer = new TunnelServer(wsListener, "127.0.0.1", targetPort);
        wsServer.start();

        // Start gRPC listener on grpcPort with token "grpc-secret"
        ListenerConfig grpcListener = new ListenerConfig("grpc", "127.0.0.1", grpcPort, "/tunnel", "grpc-secret");
        GrpcTunnelServer grpcServer = new GrpcTunnelServer(grpcListener, "127.0.0.1", targetPort);
        grpcServer.start();

        // Client 1: connect via WebSocket
        ClientConfig wsClientConfig = new ClientConfig("ws://127.0.0.1:" + wsPort + "/ws-tunnel", "ws-secret", "WS-Test", 0);
        TunnelClient wsClient = new TunnelClient(wsClientConfig);

        // Client 2: connect via gRPC
        ClientConfig grpcClientConfig = new ClientConfig("grpc://127.0.0.1:" + grpcPort, "grpc-secret", "gRPC-Test", 0);
        TunnelClient grpcClient = new TunnelClient(grpcClientConfig);

        try {
            // Start both clients simultaneously
            wsClient.start();
            grpcClient.start();
            assertEquals(TunnelClient.State.RUNNING, wsClient.state());
            assertEquals(TunnelClient.State.RUNNING, grpcClient.state());

            // Send data through WebSocket client
            try (Socket gameClient = new Socket("127.0.0.1", wsClient.localPort())) {
                gameClient.setSoTimeout(5000);
                gameClient.getOutputStream().write("hello-ws".getBytes(StandardCharsets.UTF_8));
                gameClient.getOutputStream().flush();

                byte[] buf = new byte[1024];
                int n = gameClient.getInputStream().read(buf);
                assertTrue(n > 0);
                assertEquals("echo:hello-ws", new String(buf, 0, n, StandardCharsets.UTF_8));
            }

            // Send data through gRPC client
            try (Socket gameClient = new Socket("127.0.0.1", grpcClient.localPort())) {
                gameClient.setSoTimeout(5000);
                gameClient.getOutputStream().write("hello-grpc".getBytes(StandardCharsets.UTF_8));
                gameClient.getOutputStream().flush();

                byte[] buf = new byte[1024];
                int n = gameClient.getInputStream().read(buf);
                assertTrue(n > 0);
                assertEquals("echo:hello-grpc", new String(buf, 0, n, StandardCharsets.UTF_8));
            }

            assertEquals(2, echoCount.get());

            // Verify token isolation: using ws-secret against gRPC listener fails
            ClientConfig badTokenClient = new ClientConfig("grpc://127.0.0.1:" + grpcPort, "ws-secret", "Bad-Token", 0);
            TunnelClient badClient = new TunnelClient(badTokenClient);
            try {
                assertThrows(Exception.class, badClient::start);
                assertEquals(TunnelClient.State.ERROR, badClient.state());
            } finally {
                badClient.stop();
            }

        } finally {
            wsClient.stop();
            grpcClient.stop();
            wsServer.shutdown();
            grpcServer.shutdown();
            mockMcServer.close();
            executor.shutdownNow();
        }
    }
}
