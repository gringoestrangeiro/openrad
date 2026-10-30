package io.github.gringoestrangeiro.openradplugin.util;

import java.util.concurrent.TimeUnit;

/** No Bukkit or JUnit dependency: actually executes on a Java 17 JVM in CI. */
public final class Java17ThreadCompatSmoke {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 17)
            throw new AssertionError("Smoke must run with a real Java 17 runtime");
        Thread thread = ThreadCompat.virtualFactory("orb-legacy-").newThread(() -> {});
        if (!thread.isDaemon())
            throw new AssertionError("Java 17 fallback threads must be daemon");
        thread.start();
        thread.join(2_000L);
        if (thread.isAlive())
            throw new AssertionError("Fallback thread did not stop");
        var executor = ThreadCompat.virtualExecutor();
        try {
            var result = executor.submit(() -> "JDK17-OK");
            if (!"JDK17-OK".equals(result.get(3, TimeUnit.SECONDS)))
                throw new AssertionError("Java17 candidate executor failed");
        } finally {
            executor.shutdownNow();
        }
        System.out.println("OpenRad ThreadCompat Java17 runtime smoke passed");
    }
}
