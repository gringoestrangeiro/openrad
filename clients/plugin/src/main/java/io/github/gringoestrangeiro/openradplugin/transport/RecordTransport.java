package io.github.gringoestrangeiro.openradplugin.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

/** Message transport. One owner serializes calls; close may interrupt that owner. */
public interface RecordTransport extends AutoCloseable {
    void send(byte[] data) throws IOException;

    byte[] receive(int maximum) throws IOException;

    boolean ready(Duration timeout) throws IOException;

    default boolean hasRoom(int length) {
        return true;
    }

    default void sustain() {}

    InetSocketAddress endpoint();

    @Override
    void close() throws IOException;
}
