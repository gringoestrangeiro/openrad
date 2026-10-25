package io.github.gringoestrangeiro.openradplugin.discovery;

import static io.github.gringoestrangeiro.openradplugin.link.Checksum.*;

import io.github.gringoestrangeiro.openradplugin.link.EthernetFrame;
import io.github.gringoestrangeiro.openradplugin.protocol.Wire;
import io.github.gringoestrangeiro.openradplugin.tunnel.TunnelCodec;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.regex.Pattern;

/** Minecraft Java LAN discovery only; never a general UDP forwarding endpoint. */
public record LanAdvertisement(String motd, int port) {
    public static final String GROUP = "224.0.2.60";
    public static final int PORT = 4445, MAX_PAYLOAD = 1400;
    private static final int GROUP_IP = 0xe000023c;
    private static final byte[] GROUP_MAC = {1, 0, 0x5e, 0, 2, 0x3c};
    private static final Pattern MESSAGE =
            Pattern.compile("\\[MOTD](.*?)\\[/MOTD]\\[AD]([0-9]{1,5})\\[/AD]", Pattern.DOTALL);

    public LanAdvertisement {
        if (motd == null
                || port < 1
                || port > 65535
                || motd.chars().anyMatch(c -> Character.isISOControl(c))
                || motd.contains("[MOTD]")
                || motd.contains("[/MOTD]")
                || motd.contains("[AD]")
                || motd.contains("[/AD]"))
            throw new IllegalArgumentException("invalid Minecraft LAN advertisement");
        if (message(motd, port).length > MAX_PAYLOAD)
            throw new IllegalArgumentException("LAN advertisement exceeds MTU budget");
    }

    private static byte[] message(String motd, int port) {
        return ("[MOTD]" + motd + "[/MOTD][AD]" + port + "[/AD]").getBytes(StandardCharsets.UTF_8);
    }

    public byte[] payload() {
        return message(motd, port);
    }

    public static LanAdvertisement parse(byte[] data, int offset, int length) {
        if (length < 1 || length > MAX_PAYLOAD || offset < 0 || offset > data.length - length)
            throw new IllegalArgumentException("invalid LAN payload bounds");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data, offset, length))
                            .toString();
            var match = MESSAGE.matcher(text);
            if (!match.matches()) throw new IllegalArgumentException("invalid LAN payload");
            // Two-line server MOTDs are common; the LAN list shows one line, so join them.
            String motd = match.group(1).replaceAll("\\r?\\n", " ");
            return new LanAdvertisement(motd, Integer.parseInt(match.group(2)));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid LAN UTF-8", e);
        }
    }

    /** Source is the attachment VIP; both checksums and Ethernet multicast mapping are explicit. */
    public byte[] frame(Inet4Address source, int virtualPort, int identification) {
        byte[] payload = new LanAdvertisement(motd, virtualPort).payload();
        byte[] ip = new byte[28 + payload.length];
        ip[0] = 0x45;
        put16(ip, 2, ip.length);
        put16(ip, 4, identification);
        put16(ip, 6, 0x4000); // DF: every announcement fits one Ethernet frame.
        ip[8] = 1;
        ip[9] = 17;
        put32(ip, 12, Wire.ipInt(source));
        put32(ip, 16, GROUP_IP);
        put16(ip, 10, onesComplement(ip, 0, 20));
        put16(ip, 20, PORT);
        put16(ip, 22, PORT);
        put16(ip, 24, 8 + payload.length);
        System.arraycopy(payload, 0, ip, 28, payload.length);
        byte[] pseudo = new byte[12 + ip.length - 20];
        System.arraycopy(ip, 12, pseudo, 0, 8);
        pseudo[9] = 17;
        put16(pseudo, 10, ip.length - 20);
        System.arraycopy(ip, 20, pseudo, 12, ip.length - 20);
        int checksum = onesComplement(pseudo, 0, pseudo.length);
        put16(ip, 26, checksum == 0 ? 65535 : checksum);
        return new EthernetFrame(GROUP_MAC, TunnelCodec.mac(source), EthernetFrame.IPV4, ip)
                .encode();
    }
}
