package io.github.gringoestrangeiro.openradclient;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.session.CoordinatorEndpoint;
import io.github.gringoestrangeiro.openradplugin.session.Provisioner;
import io.github.gringoestrangeiro.openradplugin.session.ServiceConfig;

import java.io.IOException;
import java.net.Inet4Address;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Gives this Minecraft client a new device identity, and with it a new 26.x virtual address, from
 * the coordinator it uses. As in the desktop's identity reset, the saved identity is replaced only
 * after the new one was issued and stored: a failure keeps the current identity working. The
 * replacement is staged in its own folder, so an issued but not yet installed identity is reused
 * by the next attempt instead of registering yet another device.
 */
public final class IdentityReplacement {
    static final String STAGING = "identity-replacement";
    private static final String DEVICE_NAME = "OpenRad Minecraft";

    @FunctionalInterface
    interface Registrar {
        Identity loadOrRegister(Path directory) throws IOException;
    }

    private IdentityReplacement() {}

    /** Call only while no runtime owns this profile; returns the new virtual address. */
    public static Inet4Address replace(Path stateDirectory) throws IOException {
        CoordinatorEndpoint coordinator =
                CoordinatorEndpoint.load(stateDirectory, ServiceConfig.BOOTSTRAP, ServiceConfig.PORT);
        return replace(coordinator.identityDirectory(), directory -> Provisioner.loadOrRegister(
                directory, DEVICE_NAME, coordinator.bootstrap(), coordinator.port(),
                session -> {}, coordinator.modulus()));
    }

    static Inet4Address replace(Path identityDirectory, Registrar registrar) throws IOException {
        Files.createDirectories(identityDirectory);
        Path lockPath = identityDirectory.resolve("identity.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("identity lock symlink not permitted");
        try (FileChannel lockFile = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try {
                lock = lockFile.tryLock();
            } catch (OverlappingFileLockException inUse) {
                lock = null;
            }
            if (lock == null) throw new IOException("OpenRad identity is still in use");
            Path staging = identityDirectory.resolve(STAGING);
            if (Files.isSymbolicLink(staging)) throw new IOException("replacement folder symlink not permitted");
            Files.createDirectories(staging);
            Inet4Address vip;
            try (Identity issued = registrar.loadOrRegister(staging)) {
                vip = issued.vip();
            }
            Path fresh = staging.resolve("identity.json");
            Path saved = identityDirectory.resolve("identity.json");
            try {
                Files.move(fresh, saved, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException sameVolumeExpected) {
                Files.move(fresh, saved, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(staging.resolve("registration.pending"));
            Files.deleteIfExists(staging);
            return vip;
        }
    }
}
