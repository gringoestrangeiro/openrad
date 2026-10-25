package io.github.gringoestrangeiro.openradplugin.transport;

import static io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec.*;
import static io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec.concat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

/** Pair only an authenticated coordinator ticket; peer SRP authentication follows pairing. */
public final class RelayTransport {
    private RelayTransport() {}

    public static FramedTcp pair(InetSocketAddress relay, byte[] ticket, Duration timeout)
            throws IOException {
        if (ticket.length == 0 || ticket.length > 65536)
            throw new IllegalArgumentException("relay ticket size");
        FramedTcp stream = FramedTcp.connect(relay, timeout);
        try {
            stream.send(concat(u32(0x010001df, 1), u32(0x0100032b, 2), encode(0x090001ca, ticket)));
            if (int32(field(parse(stream.receive(65536)), 0x010001df)) != 2)
                throw new IOException("relay ticket rejected");
            return stream;
        } catch (IOException | RuntimeException e) {
            stream.close();
            throw e;
        }
    }
}
