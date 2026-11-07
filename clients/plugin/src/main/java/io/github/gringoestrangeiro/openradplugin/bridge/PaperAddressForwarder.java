package io.github.gringoestrangeiro.openradplugin.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.kyori.adventure.key.Key;

/**
 * Makes Paper see a VPN player's virtual 26.x address instead of the bridge's 127.0.0.1.
 *
 * <p>Uses Paper's {@code ChannelInitializeListenerHolder} (present since 1.17.1) through
 * reflection, so neither Netty nor server internals are compile-time dependencies. For each
 * accepted channel whose loopback source port belongs to a live bridge flow, the player's
 * connection address is replaced on the channel's event loop. That task is queued while the
 * channel registers, so it runs after {@code channelActive} (which first stores the loopback
 * address) and before the handshake is read. Every other connection is left untouched, and
 * any reflective failure only disables the feature.
 */
public final class PaperAddressForwarder implements AutoCloseable {
    private static final String HOLDER = "io.papermc.paper.network.ChannelInitializeListenerHolder";
    private static final String LISTENER = "io.papermc.paper.network.ChannelInitializeListener";
    private static final String PACKET_HANDLER = "packet_handler";
    private static final int ATTEMPTS = 16;

    private final VirtualOrigins origins;
    private final Consumer<String> log;
    private final Key key;
    private final Method removeListener, remoteAddress, pipeline, pipelineGet, eventLoop;
    private final ConcurrentHashMap<Class<?>, Optional<Field>> addressFields =
            new ConcurrentHashMap<>();
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile boolean active = true;

    private PaperAddressForwarder(
            VirtualOrigins origins,
            Consumer<String> log,
            Key key,
            Method removeListener,
            Method remoteAddress,
            Method pipeline,
            Method pipelineGet,
            Method eventLoop) {
        this.origins = origins;
        this.log = log;
        this.key = key;
        this.removeListener = removeListener;
        this.remoteAddress = remoteAddress;
        this.pipeline = pipeline;
        this.pipelineGet = pipelineGet;
        this.eventLoop = eventLoop;
    }

    /** Installs the channel listener, or returns null (after logging) when unsupported. */
    public static PaperAddressForwarder install(
            ClassLoader serverLoader, VirtualOrigins origins, Consumer<String> log) {
        try {
            Class<?> holder = Class.forName(HOLDER, true, serverLoader);
            Class<?> listener = Class.forName(LISTENER, true, serverLoader);
            ClassLoader nettyLoader = listener.getClassLoader();
            Class<?> channel = Class.forName("io.netty.channel.Channel", false, nettyLoader);
            Class<?> channelPipeline =
                    Class.forName("io.netty.channel.ChannelPipeline", false, nettyLoader);
            Method add = holder.getMethod("addListener", Key.class, listener);
            Method has = holder.getMethod("hasListener", Key.class);
            Method remove = holder.getMethod("removeListener", Key.class);
            Key key = Key.key("openradplugin", "virtual-player-address");
            PaperAddressForwarder forwarder =
                    new PaperAddressForwarder(
                            origins,
                            log,
                            key,
                            remove,
                            channel.getMethod("remoteAddress"),
                            channel.getMethod("pipeline"),
                            channelPipeline.getMethod("get", String.class),
                            channel.getMethod("eventLoop"));
            Object proxy =
                    Proxy.newProxyInstance(
                            nettyLoader,
                            new Class<?>[] {listener},
                            (self, method, args) -> {
                                switch (method.getName()) {
                                    case "afterInitChannel":
                                        forwarder.initialized(args[0]);
                                        return null;
                                    case "hashCode":
                                        return System.identityHashCode(self);
                                    case "equals":
                                        return self == args[0];
                                    case "toString":
                                        return "OpenRadPlugin virtual player address";
                                    default:
                                        return null;
                                }
                            });
            // A previous plugin instance (for example after /reload) may have left its listener.
            if (Boolean.TRUE.equals(has.invoke(null, key))) remove.invoke(null, key);
            add.invoke(null, key, proxy);
            return forwarder;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log.accept(
                    "Este servidor nao expoe ChannelInitializeListener ("
                            + e.getClass().getSimpleName()
                            + "); jogadores da VPN aparecerao como 127.0.0.1.");
            return null;
        }
    }

    private void initialized(Object channel) {
        if (!active || origins.size() == 0) return;
        try {
            InetSocketAddress origin =
                    origins.lookup((SocketAddress) remoteAddress.invoke(channel));
            if (origin == null) return;
            Executor loop = (Executor) eventLoop.invoke(channel);
            loop.execute(new Apply(channel, loop, origin));
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
        }
    }

    private final class Apply implements Runnable {
        private final Object channel;
        private final Executor loop;
        private final InetSocketAddress origin;
        private int attempts;

        Apply(Object channel, Executor loop, InetSocketAddress origin) {
            this.channel = channel;
            this.loop = loop;
            this.origin = origin;
        }

        @Override
        public void run() {
            if (!active) return;
            try {
                Object connection = pipelineGet.invoke(pipeline.invoke(channel), PACKET_HANDLER);
                Field field = connection == null ? null : addressField(connection.getClass());
                if (connection != null && field == null) {
                    disable(null);
                    return;
                }
                Object current = field == null ? null : field.get(connection);
                if (current == null) {
                    // channelActive has not stored the address yet; retry on the same loop.
                    if (++attempts < ATTEMPTS) loop.execute(this);
                    return;
                }
                // Only replace the bridge's own loopback address, never a proxy-forwarded one.
                if (current instanceof InetSocketAddress
                        && ((InetSocketAddress) current).getAddress() != null
                        && ((InetSocketAddress) current).getAddress().isLoopbackAddress())
                    field.set(connection, origin);
            } catch (ReflectiveOperationException | RuntimeException e) {
                disable(e);
            }
        }
    }

    /** The connection's own remote-address field: named "address", else the only candidate. */
    private Field addressField(Class<?> type) {
        return addressFields
                .computeIfAbsent(
                        type,
                        t -> {
                            List<Field> candidates = new ArrayList<>();
                            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass())
                                for (Field f : c.getDeclaredFields())
                                    if (!Modifier.isStatic(f.getModifiers())
                                            && f.getType() == SocketAddress.class) {
                                        if (f.getName().equals("address")) return accessible(f);
                                        // Paper's HAProxy field records the proxy, not the player.
                                        if (!f.getName().toLowerCase(Locale.ROOT).contains("proxy"))
                                            candidates.add(f);
                                    }
                            return candidates.size() == 1
                                    ? accessible(candidates.get(0))
                                    : Optional.empty();
                        })
                .orElse(null);
    }

    private static Optional<Field> accessible(Field field) {
        try {
            field.setAccessible(true);
            return Optional.of(field);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void disable(Exception cause) {
        active = false;
        if (warned.compareAndSet(false, true))
            log.accept(
                    "Encaminhamento do IP virtual desativado ("
                            + (cause == null
                                    ? "campo de endereco nao encontrado"
                                    : cause.getClass().getSimpleName())
                            + "); jogadores da VPN aparecerao como 127.0.0.1.");
    }

    @Override
    public void close() {
        active = false;
        try {
            removeListener.invoke(null, key);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The server is shutting down or never accepted the listener.
        }
    }
}
