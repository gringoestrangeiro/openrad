package io.github.gringoestrangeiro.openradplugin.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinatorEndpointTest {
    @TempDir Path directory;

    @Test
    void customCoordinatorUsesItsOwnIdentityAndReturningToDefaultKeepsTheOldState() throws IOException {
        Path state = Files.createDirectory(directory.resolve("instância com espaço"));
        Path identity = Files.writeString(state.resolve("identity.json"), "synthetic official state");
        Path configuration = state.resolve("coordinator.properties");
        Path key = state.resolve("modulus.bin");
        assertEquals(state, load(state).identityDirectory());

        Files.writeString(configuration, "host=127.0.0.1\nport=21301\n");
        byte[] modulus = SyntheticControlService.modulus();
        Files.write(key, modulus);
        CoordinatorEndpoint local = load(state);
        assertEquals("127.0.0.1", local.bootstrap());
        assertEquals(21301, local.port());
        assertEquals(state.resolve("coordinators"), local.identityDirectory().getParent());
        byte[] padded = new byte[modulus.length + 1];
        System.arraycopy(modulus, 0, padded, 1, modulus.length);
        Files.write(key, padded);
        assertEquals(local.identityDirectory(), load(state).identityDirectory());

        byte[] replacementKey = modulus.clone();
        replacementKey[4] ^= 1;
        Files.write(key, replacementKey);
        assertNotEquals(local.identityDirectory(), load(state).identityDirectory());
        Files.delete(configuration);
        assertEquals(state, load(state).identityDirectory());
        assertEquals("synthetic official state", Files.readString(identity));
    }

    @Test
    void explicitInvalidCoordinatorNeverFallsBackToTheOfficialServer() throws IOException {
        Path configuration = directory.resolve("coordinator.properties");
        Files.writeString(configuration, "host=localhost\n");
        assertThrows(IOException.class, () -> load(directory));
        Files.writeString(configuration, "host=127.0.0.1\nport=0\n");
        assertThrows(IOException.class, () -> load(directory));
        Files.writeString(configuration, "host=127.0.0.1\n");
        assertThrows(IOException.class, () -> load(directory));

        byte[] oversized = new byte[4097];
        Arrays.fill(oversized, (byte) 1);
        Files.write(directory.resolve("modulus.bin"), oversized);
        assertThrows(IOException.class, () -> load(directory));
        Files.write(directory.resolve("modulus.bin"), new byte[] { 1 });
        assertThrows(IOException.class, () -> load(directory));
    }

    @Test
    void explicitCoordinatorFilesMustBeRegularFiles() throws IOException {
        Files.writeString(directory.resolve("coordinator.properties"), "host=127.0.0.1\n");
        Files.createDirectory(directory.resolve("modulus.bin"));
        var error = assertThrows(IOException.class, () -> load(directory));
        assertTrue(error.getMessage().contains("regular file"));
    }

    private static CoordinatorEndpoint load(Path directory) throws IOException {
        return CoordinatorEndpoint.load(directory, ServiceConfig.BOOTSTRAP, ServiceConfig.PORT);
    }
}
