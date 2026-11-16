package io.github.gringoestrangeiro.openradclient.fabric;

import java.time.Duration;

/** Two presses within a short window confirm an irreversible menu action. Render-thread only. */
final class ConfirmWindow {
    private final long windowNanos;
    private long armedUntil;
    private boolean armed;

    ConfirmWindow(Duration window) { this.windowNanos = window.toNanos(); }

    /** True when this press confirms; otherwise it arms the window. */
    boolean press(long now) {
        if (armed(now)) {
            armed = false;
            return true;
        }
        armed = true;
        armedUntil = now + windowNanos;
        return false;
    }

    boolean armed(long now) {
        if (armed && now - armedUntil >= 0) armed = false;
        return armed;
    }
}
