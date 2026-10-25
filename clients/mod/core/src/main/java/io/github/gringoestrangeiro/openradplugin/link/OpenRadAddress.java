package io.github.gringoestrangeiro.openradplugin.link;

/** Canonical OpenRad tunnel MAC mapping, from Rust tunnel::mac(vip). RID is independent of VIP. */
public final class OpenRadAddress {
    private OpenRadAddress() {}

    public static byte[] mac(int ipv4) {
        return new byte[] {
            2, 0x1a, (byte) (ipv4 >>> 24), (byte) (ipv4 >>> 16), (byte) (ipv4 >>> 8), (byte) ipv4
        };
    }
}
