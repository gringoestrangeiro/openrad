package io.github.gringoestrangeiro.openradplugin.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns only the current/retiring generation pointers and the shutdown flag. Generations are
 * closed by the caller after leaving these monitors, so shutdown never queues behind socket
 * teardown.
 */
public final class GenerationGate<T> {
    public record Handoff<T>(T current, T retiring) {}

    private final Object state = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private T current;
    private T retiring;

    public boolean stopping() {
        return stopping.get();
    }

    public void reset() {
        stopping.set(false);
    }

    public T current() {
        synchronized (state) {
            return current;
        }
    }

    /**
     * Detaches the current generation for teardown. A generation retained by a refused
     * replacement is retried; returns null after shutdown has started.
     */
    public T retire() {
        synchronized (state) {
            if (stopping.get()) return null;
            T previous = current != null ? current : retiring;
            current = null;
            retiring = previous;
            return previous;
        }
    }

    /** Clears a generation after it has stopped. */
    public void retired(T generation) {
        synchronized (state) {
            if (retiring == generation) retiring = null;
        }
    }

    /** Installs (and starts) the replacement only if shutdown has not started. */
    public boolean install(T generation, Consumer<T> start) {
        synchronized (state) {
            if (stopping.get()) return false;
            current = generation;
            if (generation != null) start.accept(generation);
            return true;
        }
    }

    /** Stops accepting replacements. */
    public void stop() {
        stopping.set(true);
    }

    /** Atomically detaches both generations; callers close them outside the monitor. */
    public Handoff<T> takeAll() {
        synchronized (state) {
            Handoff<T> handoff = new Handoff<>(current, retiring);
            current = null;
            retiring = null;
            return handoff;
        }
    }
}
