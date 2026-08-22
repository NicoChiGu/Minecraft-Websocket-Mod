package dev.terata.mctunnel.core;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public final class GrpcProtocol {
    public static final String SERVICE_NAME = "dev.terata.mctunnel.proto.TunnelService";
    public static final String METHOD_NAME = "TunnelStream";

    public static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> TOKEN_METADATA_KEY =
        Metadata.Key.of("token", Metadata.ASCII_STRING_MARSHALLER);

    public static final MethodDescriptor.Marshaller<Frame> FRAME_MARSHALLER = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(Frame value) {
            ByteBuffer buffer = FrameCodec.encode(value);
            return new ByteArrayInputStream(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        }

        @Override
        public Frame parse(InputStream stream) {
            try {
                byte[] bytes = stream.readAllBytes();
                return FrameCodec.decode(ByteBuffer.wrap(bytes));
            } catch (IOException e) {
                throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Malformed frame payload").withCause(e));
            }
        }
    };

    public static final MethodDescriptor<Frame, Frame> TUNNEL_METHOD = MethodDescriptor.<Frame, Frame>newBuilder()
        .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, METHOD_NAME))
        .setSampledToLocalTracing(true)
        .setRequestMarshaller(FRAME_MARSHALLER)
        .setResponseMarshaller(FRAME_MARSHALLER)
        .build();

    private GrpcProtocol() { }
}
