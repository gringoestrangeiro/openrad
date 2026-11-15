package io.github.gringoestrangeiro.openradplugin.transport;

import com.sun.management.ThreadMXBean;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Send-path benchmark for {@link FramedTcp}: small records over loopback, reporting elapsed time,
 * process CPU time (which includes any timer or watchdog threads) and bytes allocated on the
 * sending thread. Run after {@code gradlew compileTestJava}:
 * {@code java -cp "build/classes/java/main;build/classes/java/test"
 * io.github.gringoestrangeiro.openradplugin.transport.FramedTcpSendBenchmark [records] [size]}.
 */
public final class FramedTcpSendBenchmark {
    private FramedTcpSendBenchmark() {}

    public static void main(String[] args) throws Exception {
        int records = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 128;
        int rounds = 5;
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
        long thread = Thread.currentThread().threadId();
        byte[] payload = new byte[size];
        try (ServerSocket listener =
                new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            AtomicLong received = new AtomicLong();
            Thread reader =
                    new Thread(
                            () -> {
                                try (Socket peer = listener.accept()) {
                                    InputStream in = peer.getInputStream();
                                    byte[] sink = new byte[1 << 16];
                                    int n;
                                    while ((n = in.read(sink)) >= 0) received.addAndGet(n);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            },
                            "benchmark-reader");
            reader.setDaemon(true);
            reader.start();
            try (Socket socket =
                            new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                    FramedTcp stream = new FramedTcp(socket, Duration.ofMinutes(10))) {
                stream.sustain();
                for (int i = 0; i < 20_000; i++) stream.send(payload);
                double[] millis = new double[rounds];
                double[] bytes = new double[rounds];
                double[] cpu = new double[rounds];
                for (int round = 0; round < rounds; round++) {
                    long allocated = threads.getThreadAllocatedBytes(thread);
                    long cpuBegan = os.getProcessCpuTime();
                    long began = System.nanoTime();
                    for (int i = 0; i < records; i++) stream.send(payload);
                    long elapsed = System.nanoTime() - began;
                    cpu[round] = (os.getProcessCpuTime() - cpuBegan) / 1e3 / records;
                    allocated = threads.getThreadAllocatedBytes(thread) - allocated;
                    millis[round] = elapsed / 1e6;
                    bytes[round] = (double) allocated / records;
                    System.out.printf(
                            "round %d: %d records of %d bytes in %.1f ms (%.2f us/record, %.2f"
                                    + " process CPU us/record, %.1f allocated bytes/record)%n",
                            round + 1,
                            records,
                            size,
                            millis[round],
                            millis[round] * 1000 / records,
                            cpu[round],
                            bytes[round]);
                }
                java.util.Arrays.sort(millis);
                java.util.Arrays.sort(bytes);
                java.util.Arrays.sort(cpu);
                System.out.printf(
                        "median: %.1f ms (%.2f us/record), %.2f process CPU us/record, %.1f"
                                + " allocated bytes/record%n",
                        millis[rounds / 2],
                        millis[rounds / 2] * 1000 / records,
                        cpu[rounds / 2],
                        bytes[rounds / 2]);
            }
        }
    }
}
