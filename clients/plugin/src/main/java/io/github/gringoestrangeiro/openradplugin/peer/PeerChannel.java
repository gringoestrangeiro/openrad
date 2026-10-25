package io.github.gringoestrangeiro.openradplugin.peer;

import io.github.gringoestrangeiro.openradplugin.crypto.*;
import io.github.gringoestrangeiro.openradplugin.transport.RecordTransport;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import java.io.IOException;
import java.net.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;

/** Mutually authenticated peer channel; every path uses the same SH and service handshake. */
public final class PeerChannel implements AutoCloseable {
    public enum Path {
        DIRECT_TCP,
        DIRECT_UDP,
        RELAY
    }

    private final RecordTransport stream;
    private final Channel cipher;
    private final Path path;
    private final long peerRid;
    private byte[] peerMac;
    private int version;

    private PeerChannel(RecordTransport stream, byte[] key, Path path, long peerRid) {
        this.stream = stream;
        byte[] first = Arrays.copyOf(key, 32);
        try {
            this.cipher = new Channel(first);
        } finally {
            Arrays.fill(first, (byte) 0);
        }
        this.path = path;
        this.peerRid = peerRid;
    }

    public static PeerChannel connect(
            RecordTransport stream,
            Path path,
            byte[] password,
            long ownRid,
            Inet4Address ownIp,
            long peerRid)
            throws IOException {
        PeerChannel p = null;
        try (ShClient sh = new ShClient(peerRid, password)) {
            stream.send(sh.start());
            stream.send(sh.parameters(stream.receive(65536)));
            stream.send(sh.challenge(stream.receive(65536)));
            byte[] key = sh.confirm(stream.receive(65536));
            try {
                p = new PeerChannel(stream, key, path, peerRid);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            p.sendRequired(new byte[] {(byte) 0xef, (byte) 0xbe, (byte) 0xad, (byte) 0xde});
            byte[] guid = new byte[16];
            new SecureRandom().nextBytes(guid);
            guid[7] = (byte) ((guid[7] & 15) | 0x40);
            guid[8] = (byte) ((guid[8] & 63) | 0x80);
            p.sendRequired(guid);
            p.sendRequired(TunnelCodec.syn(ownRid, TunnelCodec.mac(ownIp)));
            TunnelCodec.Negotiated n = TunnelCodec.synack(p.receive());
            p.peerMac = n.mac();
            p.version = n.version();
            p.sendRequired(n.response());
            stream.sustain();
            return p;
        } catch (IOException | RuntimeException e) {
            if (p != null) p.close();
            else stream.close();
            throw e;
        }
    }

    public static PeerChannel accept(
            RecordTransport stream,
            Path path,
            byte[] password,
            long ownRid,
            Inet4Address ownIp,
            long peerRid)
            throws IOException {
        PeerChannel p = null;
        try (ShServer sh = new ShServer(ownRid, password)) {
            long helloUntil = System.nanoTime() + 25_000_000_000L;
            while (!stream.ready(Duration.ofMillis(100)))
                if (System.nanoTime() >= helloUntil)
                    throw new IOException("incoming SH hello timeout");
            stream.send(sh.hello(stream.receive(65536)));
            stream.send(sh.clientPublic(stream.receive(65536)));
            try (ShServer.Proof proof = sh.proof(stream.receive(65536))) {
                stream.send(proof.message());
                byte[] key = proof.key();
                try {
                    p = new PeerChannel(stream, key, path, peerRid);
                } finally {
                    Arrays.fill(key, (byte) 0);
                }
            }
            if (!Arrays.equals(
                    p.receive(), new byte[] {(byte) 0xef, (byte) 0xbe, (byte) 0xad, (byte) 0xde}))
                throw new IOException("peer race marker mismatch");
            if (p.receive().length != 16) throw new IOException("peer race GUID length");
            TunnelCodec.Negotiated n =
                    TunnelCodec.acceptSyn(p.receive(), peerRid, TunnelCodec.mac(ownIp));
            p.sendRequired(n.response());
            TunnelCodec.acceptAck(p.receive(), n.version());
            p.peerMac = n.mac();
            p.version = n.version();
            stream.sustain();
            return p;
        } catch (IOException | RuntimeException e) {
            if (p != null) p.close();
            else stream.close();
            throw e;
        }
    }

    private void sendRequired(byte[] data) throws IOException {
        if (!send(data)) throw new IOException("peer handshake transport window full");
    }

    /** Check admission before encryption: dropping encrypted bytes would break CBC chaining. */
    public boolean send(byte[] plain) throws IOException {
        int padded = Math.multiplyExact(Math.floorDiv(Math.addExact(plain.length, 24), 16), 16);
        if (!stream.hasRoom(padded)) return false;
        stream.send(cipher.encrypt(plain));
        return true;
    }

    public byte[] receive() throws IOException {
        return cipher.decrypt(stream.receive(65536));
    }

    public boolean sendFrame(byte[] frame) throws IOException {
        // Same record as send(encodeFrame(frame)); the envelope is encrypted without a copy.
        byte[] header = TunnelCodec.frameHeader(frame.length);
        int plain = header.length + frame.length;
        int padded = Math.multiplyExact(Math.floorDiv(Math.addExact(plain, 24), 16), 16);
        if (!stream.hasRoom(padded)) return false;
        stream.send(cipher.encrypt(header, frame));
        return true;
    }

    public TunnelCodec.Packet receivePacket() throws IOException {
        return cipher.decrypt(stream.receive(65536), TunnelCodec::decode);
    }

    public boolean ready(Duration timeout) throws IOException {
        return stream.ready(timeout);
    }

    public Path path() {
        return path;
    }

    public long peerRid() {
        return peerRid;
    }

    public byte[] peerMac() {
        return peerMac.clone();
    }

    public int version() {
        return version;
    }

    public InetSocketAddress endpoint() {
        return stream.endpoint();
    }

    @Override
    public void close() throws IOException {
        cipher.close();
        stream.close();
    }
}
