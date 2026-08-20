package dev.terata.mctunnel.core;

public record Frame(FrameType type, int connectionId, byte[] payload) {
    public static Frame open(int id) { return new Frame(FrameType.OPEN, id, new byte[0]); }
    public static Frame data(int id, byte[] payload) { return new Frame(FrameType.DATA, id, payload); }
    public static Frame close(int id) { return new Frame(FrameType.CLOSE, id, new byte[0]); }
    public static Frame ping(long timestamp) { return new Frame(FrameType.PING, 0, java.nio.ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array()); }
}
