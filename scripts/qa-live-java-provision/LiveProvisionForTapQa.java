import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.session.Provisioner;
import io.github.gringoestrangeiro.openradplugin.session.ServiceConfig;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LiveProvisionForTapQa {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Requires new isolated directory and unique QA node name");
        }
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(directory)) {
            throw new IllegalArgumentException("Refusing existing profile directory");
        }
        Files.createDirectories(directory);
        try (Identity identity = Provisioner.loadOrRegister(
                directory,
                args[1],
                ServiceConfig.BOOTSTRAP,
                ServiceConfig.PORT,
                session -> {})) {
            if (!Files.isRegularFile(directory.resolve("identity.json"))) {
                throw new IllegalStateException("Issued identity not durably persisted");
            }
            System.out.println("PASS: isolated Java 2.2.0 live identity registration and durable persistence");
        }
    }
}
