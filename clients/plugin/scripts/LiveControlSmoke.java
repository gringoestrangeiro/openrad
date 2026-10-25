import io.github.gringoestrangeiro.openradbridge.crypto.Identity;
import io.github.gringoestrangeiro.openradbridge.session.*;

import java.nio.file.*;
import java.time.Duration;

/** Explicit developer-only live smoke. Never loaded by the plugin or normal test task. */
public final class LiveControlSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--new-isolated-test-profile"))
            throw new IllegalArgumentException("explicit isolated profile required");
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        String name = "OpenRadBridge-DevTest";
        try (Identity identity =
                        Provisioner.loadOrRegister(
                                directory,
                                name,
                                ServiceConfig.BOOTSTRAP,
                                ServiceConfig.PORT,
                                s -> {});
                Attachment attachment =
                        Attachment.connect(identity, ServiceConfig.PORT, name, s -> {})) {
            System.out.println(
                    "LIVE_REGISTER_AUTH_ATTACH_OK vip="
                            + attachment.vip().getHostAddress()
                            + " networks="
                            + attachment.membership().networks().size()
                            + " peers="
                            + attachment.membership().peers().size());
            attachment
                    .session()
                    .send(io.github.gringoestrangeiro.openradbridge.protocol.Wire.op(4));
            if (attachment.session().ready(Duration.ofSeconds(12))) {
                byte[] reply = attachment.session().receive();
                System.out.println(
                        "LIVE_CONTROL_REPLY operation="
                                + io.github.gringoestrangeiro.openradbridge.protocol.TlvCodec
                                        .operation(reply));
            } else System.out.println("LIVE_CONTROL_NO_REPLY_WITHIN_12S");
        }
    }
}
