import io.github.gringoestrangeiro.openradplugin.peer.PeerChannel;
import io.github.gringoestrangeiro.openradplugin.transport.*;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs four real Java/canonical-Rust transport cases; requires a separately built harness. */
public final class TransportInterop {
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    static final byte[] PASSWORD = "interop-password".getBytes();

    static Inet4Address ip(String s) throws Exception {
        return (Inet4Address) InetAddress.getByName(s);
    }

    static byte[] frame(int sequence) {
        byte[] f = new byte[1514];
        for (int i = 0; i < f.length; i++) f[i] = (byte) (i + sequence);
        return f;
    }

    static void same(byte[] a, byte[] b) {
        if (!Arrays.equals(a, b)) throw new AssertionError("frame mismatch");
    }

    static InetSocketAddress endpoint(String s) {
        int colon = s.lastIndexOf(':');
        return new InetSocketAddress(
                s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
    }

    static Process process(String executable, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    static void initiate(RecordTransport stream, PeerChannel.Path path, Process child)
            throws Exception {
        try (PeerChannel p = PeerChannel.connect(stream, path, PASSWORD, 11, ip("26.0.0.1"), 22)) {
            for (int i = 0; i < 50; i++) {
                if (!p.sendFrame(frame(i))) throw new AssertionError("congestion");
                same(frame(i + 1), ((TunnelCodec.Frames) p.receivePacket()).frames().getFirst());
            }
            p.send(new byte[] {42});
            if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0)
                throw new AssertionError("canonical acceptor failed");
        }
    }

    static void accept(RecordTransport stream, PeerChannel.Path path) throws Exception {
        try (PeerChannel p = PeerChannel.accept(stream, path, PASSWORD, 22, ip("26.0.0.2"), 11)) {
            for (int i = 0; i < 50; i++) {
                same(frame(i), ((TunnelCodec.Frames) p.receivePacket()).frames().getFirst());
                if (!p.sendFrame(frame(i + 1))) throw new AssertionError("congestion");
            }
            same(new byte[] {42}, p.receive());
        }
    }

    public static void main(String[] args) throws Exception {
        String executable = args[0];
        for (String mode : List.of("tcp-server", "udp-server", "tcp-client", "udp-client")) {
            Process child = null;
            try {
                if (mode.equals("tcp-server")) {
                    child = process(executable, mode);
                    BufferedReader stdout = child.inputReader();
                    InetSocketAddress endpoint = endpoint(stdout.readLine());
                    FramedTcp stream = FramedTcp.connect(endpoint, TIMEOUT);
                    stream.peerRendezvous(11, 99, 7);
                    initiate(stream, PeerChannel.Path.DIRECT_TCP, child);
                } else if (mode.equals("udp-server")) {
                    child = process(executable, mode);
                    BufferedReader stdout = child.inputReader();
                    InetSocketAddress endpoint = endpoint(stdout.readLine());
                    DatagramSocket socket =
                            new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                    child.outputWriter().write("127.0.0.1:" + socket.getLocalPort() + "\n");
                    child.outputWriter().flush();
                    initiate(
                            ReliableUdp.connect(socket, List.of(endpoint), 81, TIMEOUT),
                            PeerChannel.Path.DIRECT_UDP,
                            child);
                } else if (mode.equals("tcp-client")) {
                    try (ServerSocket server = new ServerSocket(0, 16, ip("127.0.0.1"))) {
                        child = process(executable, mode, "127.0.0.1:" + server.getLocalPort());
                        FramedTcp stream = new FramedTcp(server.accept(), TIMEOUT);
                        stream.acceptRendezvous(11, 99);
                        accept(stream, PeerChannel.Path.DIRECT_TCP);
                    }
                } else {
                    try (DatagramSocket socket =
                            new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
                        child = process(executable, mode, "127.0.0.1:" + socket.getLocalPort());
                        InetSocketAddress endpoint = endpoint(child.inputReader().readLine());
                        accept(
                                ReliableUdp.accept(socket, List.of(endpoint), 81, TIMEOUT),
                                PeerChannel.Path.DIRECT_UDP);
                    }
                }
                if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0)
                    throw new AssertionError("canonical Rust harness failed: " + mode);
                System.out.println("PASS " + mode + " (50 bidirectional Ethernet frames)");
            } finally {
                if (child != null && child.isAlive()) child.destroyForcibly();
            }
        }
    }
}
