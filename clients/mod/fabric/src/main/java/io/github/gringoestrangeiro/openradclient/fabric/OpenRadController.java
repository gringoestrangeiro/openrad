package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientConfig;
import io.github.gringoestrangeiro.openradclient.ClientVpnRuntime;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** One serialized owner of config writes and runtime generations; UI calls never wait for I/O. */
public final class OpenRadController implements AutoCloseable {
    private final Path directory;
    private final Logger logger;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            // One admitted UI action plus a reserved shutdown task. close() can race
            // a queued action before the worker has dequeued it.
            new ArrayBlockingQueue<>(2), io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.platformFactory("openrad-client-settings-"));
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ClientConfig config = new ClientConfig(false, List.of());
    private volatile ClientVpnRuntime runtime;
    private volatile LoopbackBridge bridge = new LoopbackBridge(this::openTunnel);
    private volatile String message = "openrad.menu.loading";
    private volatile ClientVpnRuntime.CatalogSnapshot previousCatalog;
    private volatile boolean initialized;
    private volatile boolean invalidConfig;

    public OpenRadController(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    public void initialize() {
        submit(() -> {
            try {
                ClientConfig loaded;
                try { loaded = ClientConfig.load(directory); }
                catch (IOException invalid) { invalidConfig = true; throw invalid; }
                if (closed.get()) return;
                config = loaded;
                replaceBridge(loaded);
                runtime = new ClientVpnRuntime(loaded, directory.resolve("state"), logger::info);
                message = "";
                if (loaded.autoConnect()) runtime.connect();
            } finally { initialized = true; }
        });
    }

    public ClientConfig config() { return config; }
    public boolean initialized() { return initialized; }
    public boolean busy() { return busy.get(); }
    public String messageKey() {
        if (busy()) return "openrad.menu.busy";
        return visibleMessage(message, status().phase());
    }

    static String visibleMessage(String message, String phase) {
        // Never show a stale "Connecting..." after a control attachment or failure.
        return "openrad.command.connecting".equals(message) && !"CONNECTING".equals(phase)
                ? "" : message;
    }
    public ClientVpnRuntime.Snapshot status() {
        ClientVpnRuntime current = runtime;
        return current == null ? new ClientVpnRuntime.Snapshot("OFFLINE", "", List.of(), 0, "") : current.status();
    }
    public ClientVpnRuntime.CatalogSnapshot catalog() {
        ClientVpnRuntime current = runtime;
        ClientVpnRuntime.CatalogSnapshot snapshot = current == null ? null : current.minecraftCatalog();
        if (snapshot != null && !snapshot.entries().isEmpty()) previousCatalog = snapshot;
        return snapshot == null || snapshot.entries().isEmpty() ? previousCatalog : snapshot;
    }
    public List<ClientVpnRuntime.LanServer> lanServers() {
        ClientVpnRuntime current = runtime;
        return current == null ? List.of() : current.lanServers();
    }
    public InetSocketAddress route(InetSocketAddress destination) {
        return active() ? bridgeRoute(destination) : destination;
    }
    InetSocketAddress bridgeRoute(InetSocketAddress destination) { return bridge.route(destination); }
    public boolean active() { return active(status().phase()); }
    public static boolean active(String phase) {
        return !List.of("OFFLINE", "STOPPED", "FAILED").contains(phase);
    }

    public boolean connect(ClientConfig draft) {
        return submit(() -> {
            apply(draft);
            if (closed.get()) return;
            ClientVpnRuntime current = runtime;
            message = current != null && current.connect() ? "openrad.command.connecting" : "openrad.menu.no_networks";
        });
    }
    public boolean disconnect() {
        return submit(() -> {
            bridge.closeActive();
            ClientVpnRuntime current = runtime;
            if (current != null) {
                current.disconnect();
                if (!current.awaitStopped(Duration.ofSeconds(15))) throw new IOException("runtime stop timed out");
            }
            message = "openrad.command.disconnected";
        });
    }
    public boolean save(ClientConfig draft) {
        return submit(() -> {
            boolean reconnect = active();
            boolean changed = !draft.equals(config) || runtime == null;
            apply(draft);
            if (closed.get()) return;
            if (changed && reconnect && runtime != null) runtime.connect();
            message = "openrad.menu.saved";
        });
    }

    private void apply(ClientConfig desired) throws IOException, InterruptedException {
        if (desired.equals(config) && runtime != null) return;
        if (invalidConfig) preserveInvalidConfig();
        desired.save(directory); // A failed write leaves the current working session intact.
        ClientVpnRuntime old = runtime;
        if (old != null) {
            ClientVpnRuntime.CatalogSnapshot cached = old.minecraftCatalog();
            if (!cached.entries().isEmpty()) previousCatalog = cached;
            bridge.closeActive();
            old.close();
            if (!old.awaitStopped(Duration.ofSeconds(15))) throw new IOException("old runtime still owns identity");
        }
        if (closed.get()) return;
        config = desired;
        replaceBridge(desired);
        // Never open the same persisted identity until its previous owner has fully stopped.
        runtime = new ClientVpnRuntime(desired, directory.resolve("state"), logger::info);
    }

    /** Corrupt preferences remain available for repair; the first save never destroys them. */
    private void preserveInvalidConfig() throws IOException {
        Path file = directory.resolve("openrad-client.properties");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            Path backup = directory.resolve("openrad-client.properties.invalid-" + System.currentTimeMillis());
            Files.move(file, backup);
            logger.warning("OpenRad kept the invalid configuration as " + backup.getFileName());
        }
        invalidConfig = false;
    }

    private void replaceBridge(ClientConfig desired) {
        bridge.close();
        bridge = new LoopbackBridge(this::openTunnel, desired.maxConnections());
    }
    private LoopbackBridge.Tunnel openTunnel(Inet4Address address, int port) throws IOException {
        ClientVpnRuntime current = runtime;
        if (current == null) throw new IOException("OpenRad configuration unavailable");
        var connection = current.openTcp(address, port, Duration.ofSeconds(config.connectionTimeoutSeconds()));
        return new LoopbackBridge.Tunnel() {
            public java.io.InputStream input() { return connection.input(); }
            public java.io.OutputStream output() { return connection.output(); }
            public void shutdownOutput() throws IOException { connection.shutdownOutput(); }
            public void close() throws IOException { connection.close(); }
        };
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private boolean submit(Action action) {
        if (closed.get() || !busy.compareAndSet(false, true)) return false;
        try {
            worker.execute(() -> {
                try { if (!closed.get()) action.run(); }
                catch (Exception failure) {
                    message = runtime == null ? "openrad.menu.config_error" : "openrad.menu.save_failed";
                    logger.warning("OpenRad settings action failed (" + failure.getClass().getSimpleName() + ")");
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                } finally { busy.set(false); }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            busy.set(false);
            return false;
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.execute(() -> {
            bridge.close();
            ClientVpnRuntime current = runtime;
            if (current != null) current.close();
        });
        worker.shutdown();
    }
}
