package io.github.gringoestrangeiro.openradplugin.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java 17 compatible thread creation, preserving virtual threads when running
 * on Java 21+. Older servers use bounded daemon platform threads instead.
 */
public final class ThreadCompat {
    private ThreadCompat() {}

    public static ThreadFactory platformFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static Thread platformUnstarted(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public static Thread platformStart(String name, Runnable task) {
        Thread thread = platformUnstarted(name, task);
        thread.start();
        return thread;
    }

    public static ThreadFactory virtualFactory(String prefix) {
        try {
            // Interface is public; do not reflect on the package-private
            // implementation of Thread.Builder.OfVirtual.
            Class<?> builderType = Class.forName("java.lang.Thread$Builder$OfVirtual");
            Method start = Thread.class.getMethod("ofVirtual");
            Object builder = start.invoke(null);
            Object named = builderType.getMethod("name", String.class, long.class)
                    .invoke(builder, prefix, 0L);
            return (ThreadFactory) builderType.getMethod("factory").invoke(named);
        } catch (ClassNotFoundException | NoSuchMethodException |
                 IllegalAccessException | InvocationTargetException e) {
            return platformFactory(prefix);
        }
    }

    public static Thread virtualUnstarted(String name, Runnable task) {
        return virtualFactory(name + "-").newThread(task);
    }

    public static Thread virtualStart(String name, Runnable task) {
        Thread worker = virtualUnstarted(name, task);
        worker.start();
        return worker;
    }

    public static ExecutorService virtualExecutor() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // Avoid unbounded thread creation on Java 17. Race coordinators
            // typically submit only a handful of concurrent candidates.
            return new ThreadPoolExecutor(0, 32, 30L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), platformFactory("orb-race-"),
                    new ThreadPoolExecutor.CallerRunsPolicy());
        }
    }
}
