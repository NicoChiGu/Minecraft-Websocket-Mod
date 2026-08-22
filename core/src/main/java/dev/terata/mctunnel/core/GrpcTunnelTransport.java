package dev.terata.mctunnel.core;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class GrpcTunnelTransport implements ITunnelTransport {
    private final URI uri;
    private final String token;
    private final Listener listener;
    private volatile ManagedChannel channel;
    private volatile StreamObserver<Frame> requestObserver;
    private volatile boolean open;
    private volatile boolean closed;

    public GrpcTunnelTransport(URI uri, String token, Listener listener) {
        this.uri = uri;
        this.token = token == null ? "" : token;
        this.listener = listener;
    }

    @Override
    public void connect(long timeout, TimeUnit unit) throws Exception {
        closed = false;
        open = false;

        String scheme = uri.getScheme();
        boolean secure = "grpcs".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            host = uri.getAuthority() != null ? uri.getAuthority() : "127.0.0.1";
        }
        int port = uri.getPort();
        if (port <= 0) {
            port = secure ? 443 : 80;
        }

        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
        if (secure) {
            builder.useTransportSecurity();
        } else {
            builder.usePlaintext();
        }
        builder.keepAliveTime(30, TimeUnit.SECONDS)
               .keepAliveTimeout(10, TimeUnit.SECONDS)
               .keepAliveWithoutCalls(true);

        ClientInterceptor authInterceptor = new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions,
                Channel next
            ) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.put(GrpcProtocol.AUTHORIZATION_METADATA_KEY, "Bearer " + token);
                        headers.put(GrpcProtocol.TOKEN_METADATA_KEY, token);
                        super.start(responseListener, headers);
                    }
                };
            }
        };

        ManagedChannel ch = builder.intercept(authInterceptor).build();
        this.channel = ch;

        CountDownLatch connectLatch = new CountDownLatch(1);
        AtomicReference<Throwable> connectError = new AtomicReference<>();

        StreamObserver<Frame> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(Frame frame) {
                if (!open) {
                    open = true;
                    connectLatch.countDown();
                    if (listener != null) listener.onTransportOpen();
                }
                if (listener != null && !closed) {
                    listener.onFrameReceived(frame);
                }
            }

            @Override
            public void onError(Throwable t) {
                boolean wasOpen = open;
                open = false;
                if (!wasOpen) {
                    connectError.set(t);
                    connectLatch.countDown();
                }
                if (listener != null && !closed) {
                    Status status = Status.fromThrowable(t);
                    String description = status.getDescription() == null ? t.getMessage() : status.getDescription();
                    listener.onTransportClose(status.getCode().value(), description);
                }
            }

            @Override
            public void onCompleted() {
                open = false;
                if (listener != null && !closed) {
                    listener.onTransportClose(0, "Stream completed");
                }
            }
        };

        StreamObserver<Frame> reqObs = ClientCalls.asyncBidiStreamingCall(
            ch.newCall(GrpcProtocol.TUNNEL_METHOD, CallOptions.DEFAULT),
            responseObserver
        );
        this.requestObserver = reqObs;

        // 发送初始 ping 握手帧以验证双向流和鉴权
        long handshakeTimestamp = System.currentTimeMillis();
        synchronized (reqObs) {
            reqObs.onNext(Frame.ping(handshakeTimestamp));
        }

        if (!connectLatch.await(timeout, unit)) {
            close();
            throw new IOException("gRPC gateway connection timeout");
        }

        Throwable error = connectError.get();
        if (error != null) {
            close();
            Status status = Status.fromThrowable(error);
            if (status.getCode() == Status.Code.UNAUTHENTICATED) {
                throw new IOException("Unauthorized: " + status.getDescription());
            }
            if (error instanceof Exception e) throw e;
            throw new IOException("gRPC connection failed: " + error.getMessage(), error);
        }
    }

    @Override
    public void send(Frame frame) {
        StreamObserver<Frame> observer = requestObserver;
        if (observer != null && open && !closed) {
            try {
                synchronized (observer) {
                    if (open && !closed) {
                        observer.onNext(frame);
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    @Override
    public boolean isOpen() {
        return open && !closed && channel != null && !channel.isShutdown();
    }

    @Override
    public void close() {
        closed = true;
        open = false;
        StreamObserver<Frame> observer = requestObserver;
        requestObserver = null;
        if (observer != null) {
            try {
                synchronized (observer) {
                    observer.onCompleted();
                }
            } catch (Exception ignored) { }
        }
        ManagedChannel ch = channel;
        channel = null;
        if (ch != null) {
            ch.shutdown();
            try {
                if (!ch.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    ch.shutdownNow();
                }
            } catch (InterruptedException e) {
                ch.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean closeAndAwait(long timeout, TimeUnit unit) {
        closed = true;
        open = false;
        StreamObserver<Frame> observer = requestObserver;
        requestObserver = null;
        if (observer != null) {
            try {
                synchronized (observer) {
                    observer.onCompleted();
                }
            } catch (Exception ignored) { }
        }
        ManagedChannel ch = channel;
        channel = null;
        if (ch == null || ch.isTerminated()) return true;

        ch.shutdown();
        try {
            boolean terminated = ch.awaitTermination(timeout, unit);
            if (!terminated) {
                ch.shutdownNow();
            }
            return terminated;
        } catch (InterruptedException e) {
            ch.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
