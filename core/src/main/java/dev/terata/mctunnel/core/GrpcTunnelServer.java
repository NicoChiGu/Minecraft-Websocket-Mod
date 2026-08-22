package dev.terata.mctunnel.core;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class GrpcTunnelServer {
    private final ServerConfig config;
    private Server server;

    public GrpcTunnelServer(ServerConfig config) {
        this.config = config;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        ServerServiceDefinition serviceDefinition = ServerServiceDefinition.builder(GrpcProtocol.SERVICE_NAME)
            .addMethod(GrpcProtocol.TUNNEL_METHOD, ServerCalls.asyncBidiStreamingCall((responseObserver) ->
                new GrpcSessionHandler(config, responseObserver)))
            .build();

        server = NettyServerBuilder.forAddress(new InetSocketAddress(config.bindHost(), config.bindPort()))
            .addService(serviceDefinition)
            .intercept(new AuthInterceptor(config.token()))
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .build()
            .start();
    }

    public synchronized void shutdown() {
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            server = null;
        }
    }

    public int getPort() {
        return server == null ? config.bindPort() : server.getPort();
    }

    private static final class AuthInterceptor implements ServerInterceptor {
        private final String expectedToken;

        AuthInterceptor(String expectedToken) {
            this.expectedToken = expectedToken == null ? "" : expectedToken;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
        ) {
            String authHeader = headers.get(GrpcProtocol.AUTHORIZATION_METADATA_KEY);
            String token = null;
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring("Bearer ".length()).trim();
            } else {
                token = headers.get(GrpcProtocol.TOKEN_METADATA_KEY);
            }

            if (token == null || !constantTimeEquals(expectedToken, token)) {
                call.close(Status.UNAUTHENTICATED.withDescription("Unauthorized: invalid or missing token"), new Metadata());
                return new ServerCall.Listener<>() { };
            }
            return next.startCall(call, headers);
        }

        private static boolean constantTimeEquals(String a, String b) {
            byte[] aa = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (aa.length != bb.length) return false;
            int diff = 0;
            for (int i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
            return diff == 0;
        }
    }

    private static final class GrpcSessionHandler implements StreamObserver<Frame> {
        private final ServerConfig config;
        private final StreamObserver<Frame> responseObserver;
        private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
        private volatile boolean closed;

        GrpcSessionHandler(ServerConfig config, StreamObserver<Frame> responseObserver) {
            this.config = config;
            this.responseObserver = responseObserver;
        }

        @Override
        public void onNext(Frame frame) {
            if (closed || frame == null) return;
            try {
                switch (frame.type()) {
                    case OPEN -> openTarget(frame.connectionId());
                    case DATA -> {
                        Socket socket = sockets.get(frame.connectionId());
                        if (socket != null && !socket.isClosed()) {
                            socket.getOutputStream().write(frame.payload());
                            socket.getOutputStream().flush();
                        }
                    }
                    case CLOSE -> closeSocket(sockets.remove(frame.connectionId()));
                    case PING -> sendFrame(new Frame(FrameType.PONG, 0, frame.payload()));
                    default -> { }
                }
            } catch (IOException e) {
                closeSocket(sockets.remove(frame.connectionId()));
                sendFrame(Frame.close(frame.connectionId()));
            }
        }

        private void openTarget(int id) throws IOException {
            if (sockets.containsKey(id)) return;
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(config.targetHost(), config.targetPort()), 5000);
            sockets.put(id, socket);
            Thread reader = new Thread(() -> pumpTarget(id, socket), "mc-grpc-server-" + id);
            reader.setDaemon(true);
            reader.start();
        }

        private void pumpTarget(int id, Socket socket) {
            byte[] buffer = new byte[32 * 1024];
            try {
                int n;
                while (!closed && (n = socket.getInputStream().read(buffer)) >= 0) {
                    if (n == 0) continue;
                    byte[] data = Arrays.copyOf(buffer, n);
                    sendFrame(Frame.data(id, data));
                }
            } catch (IOException ignored) {
            } finally {
                sockets.remove(id, socket);
                closeSocket(socket);
                sendFrame(Frame.close(id));
            }
        }

        private void sendFrame(Frame frame) {
            if (closed) return;
            synchronized (responseObserver) {
                if (!closed) {
                    try {
                        responseObserver.onNext(frame);
                    } catch (Exception ignored) {
                        closeAll();
                    }
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            closeAll();
        }

        @Override
        public void onCompleted() {
            closeAll();
            synchronized (responseObserver) {
                try {
                    responseObserver.onCompleted();
                } catch (Exception ignored) { }
            }
        }

        private void closeAll() {
            closed = true;
            sockets.values().forEach(GrpcTunnelServer::closeSocket);
            sockets.clear();
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
