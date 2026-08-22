package dev.terata.mctunnel.core;

import java.util.concurrent.TimeUnit;

public interface ITunnelTransport {
    interface Listener {
        void onTransportOpen();
        void onFrameReceived(Frame frame);
        void onTransportClose(int code, String reason);
        void onTransportError(Exception ex);
    }

    void connect(long timeout, TimeUnit unit) throws Exception;
    void send(Frame frame);
    boolean isOpen();
    void close();
    boolean closeAndAwait(long timeout, TimeUnit unit);
}
