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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GrpcTunnelLifecycleTest {

    @Test
    void startsAndStopsAgainstLocalGrpcGateway() throws Exception {
        int gatewayPort;
        try (ServerSocket res = new ServerSocket(0)) { gatewayPort = res.getLocalPort(); }

        ServerConfig serverConfig = new ServerConfig("grpc", "127.0.0.1", gatewayPort, "127.0.0.1", 25565, "test-token", "/tunnel", false);
        GrpcTunnelServer server = new GrpcTunnelServer(serverConfig);
        server.start();

        ClientConfig clientConfig = new ClientConfig("grpc://127.0.0.1:" + gatewayPort, "test-token", "test", 0);
        TunnelClient client = new TunnelClient(clientConfig);
        try {
            client.start();
            assertEquals(TunnelClient.State.RUNNING, client.state());
            assertTrue(client.localPort() > 0);
        } finally {
            client.stop();
            server.shutdown();
        }
        assertEquals(TunnelClient.State.STOPPED, client.state());
    }

    @Test
    void failsWithInvalidToken() throws Exception {
        int gatewayPort;
        try (ServerSocket res = new ServerSocket(0)) { gatewayPort = res.getLocalPort(); }

        ServerConfig serverConfig = new ServerConfig("grpc", "127.0.0.1", gatewayPort, "127.0.0.1", 25565, "secret-token", "/tunnel", false);
        GrpcTunnelServer server = new GrpcTunnelServer(serverConfig);
        server.start();

        ClientConfig clientConfig = new ClientConfig("grpc://127.0.0.1:" + gatewayPort, "wrong-token", "test", 0);
        TunnelClient client = new TunnelClient(clientConfig);
        try {
            assertThrows(Exception.class, client::start);
            assertEquals(TunnelClient.State.ERROR, client.state());
        } finally {
            client.stop();
            server.shutdown();
        }
    }

    @Test
    void transfersDataBidirectionallyThroughGrpc() throws Exception {
        int targetPort;
        try (ServerSocket res = new ServerSocket(0)) { targetPort = res.getLocalPort(); }

        int gatewayPort;
        try (ServerSocket res = new ServerSocket(0)) { gatewayPort = res.getLocalPort(); }

        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch mockServerReady = new CountDownLatch(1);
        AtomicReference<String> serverReceived = new AtomicReference<>();

        ServerSocket mockMcServer = new ServerSocket(targetPort);
        executor.submit(() -> {
            mockServerReady.countDown();
            try (Socket socket = mockMcServer.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[1024];
                int n = in.read(buf);
                if (n > 0) {
                    serverReceived.set(new String(buf, 0, n, StandardCharsets.UTF_8));
                    out.write("pong-minecraft-reply".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) { }
        });

        assertTrue(mockServerReady.await(3, TimeUnit.SECONDS));

        ServerConfig serverConfig = new ServerConfig("grpc", "127.0.0.1", gatewayPort, "127.0.0.1", targetPort, "auth-token", "/tunnel", false);
        GrpcTunnelServer grpcServer = new GrpcTunnelServer(serverConfig);
        grpcServer.start();

        ClientConfig clientConfig = new ClientConfig("grpc://127.0.0.1:" + gatewayPort, "auth-token", "test", 0);
        TunnelClient client = new TunnelClient(clientConfig);
        try {
            client.start();
            assertEquals(TunnelClient.State.RUNNING, client.state());

            // 模拟客户端连接本地监听端口
            try (Socket gameClient = new Socket("127.0.0.1", client.localPort())) {
                gameClient.setSoTimeout(5000);
                OutputStream clientOut = gameClient.getOutputStream();
                InputStream clientIn = gameClient.getInputStream();

                clientOut.write("ping-minecraft-client".getBytes(StandardCharsets.UTF_8));
                clientOut.flush();

                byte[] replyBuf = new byte[1024];
                int readBytes = clientIn.read(replyBuf);
                assertTrue(readBytes > 0);
                String reply = new String(replyBuf, 0, readBytes, StandardCharsets.UTF_8);

                assertEquals("ping-minecraft-client", serverReceived.get());
                assertEquals("pong-minecraft-reply", reply);
            }
        } finally {
            client.stop();
            grpcServer.shutdown();
            mockMcServer.close();
            executor.shutdownNow();
        }
    }
}
