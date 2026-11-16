package io.github.gringoestrangeiro.openradclient.fabric;

import io.github.gringoestrangeiro.openradclient.ClientConfig;
import io.github.gringoestrangeiro.openradclient.ClientVpnRuntime;
import io.github.gringoestrangeiro.openradclient.IdentityReplacement;
import io.github.gringoestrangeiro.openradclient.virtual.OutboundTcpStack;

import io.netty.channel.ChannelFuture;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.function.Function;
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
    /** The runtime operations the controller needs; a seam so handover can be tested without a network. */
    interface Engine extends AutoCloseable {
        ClientVpnRuntime.Snapshot status();
        ClientVpnRuntime.CatalogSnapshot minecraftCatalog();
        List<ClientVpnRuntime.LanServer> lanServers();
        OutboundTcpStack.Connection openTcp(Inet4Address destination, int port, Duration timeout) throws IOException;
        boolean connect();
        void disconnect();
        boolean awaitStopped(Duration timeout) throws InterruptedException;
        @Override void close();
    }

    @FunctionalInterface interface EngineFactory {
        Engine create(ClientConfig config, Path stateDirectory, Logger logger);
    }

    /** Issues a new device identity (and 26.x address); called only while no runtime owns it. */
    @FunctionalInterface interface IdentityRenewer {
        Inet4Address renew(Path stateDirectory) throws IOException;
    }

    private static final class RuntimeEngine implements Engine {
        private final ClientVpnRuntime runtime;
        RuntimeEngine(ClientConfig config, Path stateDirectory, Logger logger) {
            runtime = new ClientVpnRuntime(config, stateDirectory, logger::info);
        }
        public ClientVpnRuntime.Snapshot status() { return runtime.status(); }
        public ClientVpnRuntime.CatalogSnapshot minecraftCatalog() { return runtime.minecraftCatalog(); }
        public List<ClientVpnRuntime.LanServer> lanServers() { return runtime.lanServers(); }
        public OutboundTcpStack.Connection openTcp(Inet4Address destination, int port, Duration timeout) throws IOException {
            return runtime.openTcp(destination, port, timeout);
        }
        public boolean connect() { return runtime.connect(); }
        public void disconnect() { runtime.disconnect(); }
        public boolean awaitStopped(Duration timeout) throws InterruptedException { return runtime.awaitStopped(timeout); }
        public void close() { runtime.close(); }
    }

    /** The previous runtime did not stop in time; its identity is still owned, so nothing new may open it. */
    private static final class StopTimeoutException extends IOException {
        private final String messageKey;
        StopTimeoutException(String messageKey, String detail) {
            super(detail);
            this.messageKey = messageKey;
        }
    }

    private final Path directory;
    private final Logger logger;
    private final EngineFactory engines;
    private final Duration stopTimeout;
    private final IdentityRenewer identities;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            // One admitted UI action plus a reserved shutdown task. close() can race
            // a queued action before the worker has dequeued it.
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("openrad-client-settings").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ClientConfig config = new ClientConfig(false, List.of());
    private volatile Engine runtime;
    /** A closed runtime that has not finished stopping; only the worker touches it. */
    private Engine stopping;
    private volatile LoopbackBridge bridge = new LoopbackBridge(this::openTunnel);
    private volatile String message = "openrad.menu.loading";
    private volatile ClientVpnRuntime.CatalogSnapshot previousCatalog;
    private volatile boolean initialized;
    private volatile boolean invalidConfig;

    public OpenRadController(Path directory, Logger logger) {
        this(directory, logger, RuntimeEngine::new, Duration.ofSeconds(15));
    }
    OpenRadController(Path directory, Logger logger, EngineFactory engines, Duration stopTimeout) {
        this(directory, logger, engines, stopTimeout, IdentityReplacement::replace);
    }
    OpenRadController(Path directory, Logger logger, EngineFactory engines, Duration stopTimeout,
                      IdentityRenewer identities) {
        this.directory = directory;
        this.logger = logger;
        this.engines = engines;
        this.stopTimeout = stopTimeout;
        this.identities = identities;
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
                runtime = engines.create(loaded, directory.resolve("state"), logger);
                message = "";
                if (loaded.autoConnect()) runtime.connect();
            } finally { initialized = true; }
        });
    }

    public ClientConfig config() { return config; }
    public boolean initialized() { return initialized; }
    public boolean invalidConfig() { return invalidConfig; }
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
    /** Failure and retry details are actionable; other phases already read well from the phase alone. */
    static String visibleDetail(String phase, String detail) {
        return ("FAILED".equals(phase) || "RETRY_WAIT".equals(phase)) && detail != null ? detail : "";
    }
    public ClientVpnRuntime.Snapshot status() {
        Engine current = runtime;
        return current == null ? new ClientVpnRuntime.Snapshot("OFFLINE", "", List.of(), 0, "") : current.status();
    }
    public ClientVpnRuntime.CatalogSnapshot catalog() {
        Engine current = runtime;
        ClientVpnRuntime.CatalogSnapshot snapshot = current == null ? null : current.minecraftCatalog();
        if (snapshot != null && !snapshot.entries().isEmpty()) previousCatalog = snapshot;
        return snapshot == null || snapshot.entries().isEmpty() ? previousCatalog : snapshot;
    }
    public List<ClientVpnRuntime.LanServer> lanServers() {
        Engine current = runtime;
        return current == null ? List.of() : current.lanServers();
    }
    public InetSocketAddress route(InetSocketAddress destination) {
        return active() ? bridgeRoute(destination) : destination;
    }
    InetSocketAddress bridgeRoute(InetSocketAddress destination) { return bridge.route(destination); }
    ChannelFuture connect(InetSocketAddress destination, boolean legacyPing,
                          Function<InetSocketAddress, ChannelFuture> connect) {
        return active() ? bridge.connect(destination, legacyPing, connect) : connect.apply(destination);
    }
    public boolean active() { return active(status().phase()); }
    public static boolean active(String phase) {
        return !List.of("OFFLINE", "STOPPED", "FAILED").contains(phase);
    }

    public boolean connect(ClientConfig draft) {
        return submit(() -> connectNow(draft));
    }
    /**
     * Connects with the configuration on disk. Unlike a menu draft, this never replaces a file that
     * failed to load: only an explicit save from the menu may preserve and overwrite it.
     */
    public boolean connectSaved() {
        return submit(() -> {
            if (invalidConfig) {
                message = "openrad.menu.config_invalid";
                return;
            }
            connectNow(config);
        });
    }
    private void connectNow(ClientConfig draft) throws IOException, InterruptedException {
        apply(draft);
        if (closed.get()) return;
        Engine current = runtime;
        if (current != null && current.connect()) message = "openrad.command.connecting";
        else message = draft.networks().isEmpty() && draft.minecraftSelectors().isEmpty()
                ? "openrad.menu.no_networks" : "openrad.menu.not_started";
    }
    public boolean disconnect() {
        return submit(() -> {
            bridge.closeActive();
            Engine current = runtime;
            if (current != null) {
                current.disconnect();
                if (!current.awaitStopped(stopTimeout))
                    throw new StopTimeoutException("openrad.menu.disconnect_timeout", "runtime stop timed out");
            }
            message = "openrad.command.disconnected";
        });
    }
    /**
     * A new device identity and 26.x address from the current coordinator, like the desktop's
     * identity reset: the VPN stops first and, if the new identity cannot be issued, the previous
     * one keeps working. Networks joined by the old identity have to be joined again.
     */
    public boolean renewIdentity() {
        return submit(() -> {
            boolean reconnect = active();
            bridge.closeActive();
            Engine old = runtime;
            if (old != null) {
                old.close();
                runtime = null;
                stopping = old;
            }
            if (stopping != null) {
                if (!stopping.awaitStopped(stopTimeout))
                    throw new StopTimeoutException("openrad.menu.handover_timeout", "old runtime still owns identity");
                stopping = null;
            }
            if (closed.get()) return;
            String outcome = "openrad.menu.new_ip_done";
            try {
                Inet4Address address = identities.renew(directory.resolve("state"));
                logger.info("OpenRad issued a new device identity with virtual IP " + address.getHostAddress());
            } catch (IOException failure) {
                outcome = "openrad.menu.new_ip_failed";
                logger.warning("OpenRad kept the previous identity; a new one could not be issued ("
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")");
            }
            replaceBridge(config);
            runtime = engines.create(config, directory.resolve("state"), logger);
            if (reconnect) runtime.connect();
            message = outcome;
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
        Engine old = runtime;
        if (old != null) {
            ClientVpnRuntime.CatalogSnapshot cached = old.minecraftCatalog();
            if (!cached.entries().isEmpty()) previousCatalog = cached;
            bridge.closeActive();
            old.close();
            // The closed runtime is never reused; the new configuration is current from here on.
            runtime = null;
            config = desired;
            stopping = old;
        }
        if (stopping != null) {
            // Never open the same persisted identity until its previous owner has fully stopped;
            // a failed handover is retried by the next connect or save.
            if (!stopping.awaitStopped(stopTimeout))
                throw new StopTimeoutException("openrad.menu.handover_timeout", "old runtime still owns identity");
            stopping = null;
        }
        if (closed.get()) return;
        config = desired;
        replaceBridge(desired);
        runtime = engines.create(desired, directory.resolve("state"), logger);
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
        Engine current = runtime;
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
                    message = failure instanceof StopTimeoutException timeout ? timeout.messageKey
                            : runtime == null ? "openrad.menu.config_error" : "openrad.menu.save_failed";
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
            Engine current = runtime;
            if (current != null) current.close();
        });
        worker.shutdown();
    }
}
