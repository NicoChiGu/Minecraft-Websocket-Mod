package dev.terata.mctunnel.core;

import java.nio.ByteBuffer;

public final class FrameCodec {
    public static final byte VERSION = 1;
    private static final int HEADER_SIZE = 6;

    private FrameCodec() {}

    public static ByteBuffer encode(Frame frame) {
        byte[] payload = frame.payload() == null ? new byte[0] : frame.payload();
        ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        out.put(VERSION);
        out.put((byte) frame.type().id);
        out.putInt(frame.connectionId());
        out.put(payload);
        out.flip();
        return out;
    }

    public static Frame decode(ByteBuffer input) {
        ByteBuffer in = input.slice();
        if (in.remaining() < HEADER_SIZE) throw new IllegalArgumentException("Tunnel frame too short");
        byte version = in.get();
        if (version != VERSION) throw new IllegalArgumentException("Unsupported tunnel protocol version: " + version);
        FrameType type = FrameType.fromId(Byte.toUnsignedInt(in.get()));
        int id = in.getInt();
        byte[] payload = new byte[in.remaining()];
        in.get(payload);
        return new Frame(type, id, payload);
    }
}
