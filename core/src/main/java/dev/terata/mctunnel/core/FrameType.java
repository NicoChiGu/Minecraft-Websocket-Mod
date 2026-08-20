package dev.terata.mctunnel.core;

public enum FrameType {
    OPEN(1), DATA(2), CLOSE(3), PING(4), PONG(5), ERROR(6);

    public final int id;
    FrameType(int id) { this.id = id; }

    public static FrameType fromId(int id) {
        for (FrameType type : values()) if (type.id == id) return type;
        throw new IllegalArgumentException("Unknown frame type: " + id);
    }
}
