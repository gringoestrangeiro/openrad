package io.github.gringoestrangeiro.openradclient;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bounded wait for the peer workers of a closed attachment generation, and the hand-over of the
 * identity lock to a keeper when some of them do not stop: the identity must never be owned by two
 * generations at once, but a stuck worker must not pin the control thread (and with it every later
 * connect) forever.
 */
final class PeerShutdown {
    private PeerShutdown() {}

    interface Stoppable {
        /** True once every worker and cleanup task of the generation has finished. */
        boolean awaitStopped(Duration timeout) throws InterruptedException;

        /** Counts of what is still running, for diagnostics. */
        String describeRunning();
    }

    /**
     * Waits up to {@code bound} for the generation to stop. Interrupts do not shorten the wait (the
     * identity lock depends on it) but are restored to the calling thread afterwards.
     */
    static boolean await(Stoppable generation, Duration bound, Consumer<String> logger) {
        long deadline = System.nanoTime() + bound.toNanos();
        boolean interrupted = Thread.interrupted();
        boolean stopped = false;
        try {
            while (!stopped) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    stopped = generation.awaitStopped(Duration.ofNanos(remaining));
                } catch (InterruptedException signal) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!stopped)
            logger.accept("Peer workers of the closed OpenRad session did not stop within "
                    + bound.toSeconds() + " s (" + generation.describeRunning()
                    + "); the identity stays locked until they finish");
        return stopped;
    }

    /**
     * Keeps {@code identityLock} until every pending generation has stopped, then closes it. The lock
     * is deliberately never released while one of them may still use the identity.
     */
    static Thread holdUntilStopped(List<? extends Stoppable> pending, AutoCloseable identityLock,
                                   Consumer<String> logger) {
        List<Stoppable> generations = List.copyOf(pending);
        Thread keeper = io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.platformUnstarted(
                "openrad-client-identity-keeper", () -> {
            try {
                for (Stoppable generation : generations)
                    while (!generation.awaitStopped(Duration.ofSeconds(1))) { }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                logger.accept("OpenRad identity keeper interrupted; the identity lock is kept until exit");
                return;
            }
            try {
                identityLock.close();
                logger.accept("Previous OpenRad peer workers stopped; identity released");
            } catch (Exception failure) {
                logger.accept("Unable to release the OpenRad identity lock ("
                        + failure.getClass().getSimpleName() + ")");
            }
        });
        keeper.start();
        return keeper;
    }
}
