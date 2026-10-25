package io.github.gringoestrangeiro.openradplugin.session;

import java.util.HexFormat;

/** Public service values copied from OpenRad 2.0.2 src/config.rs. */
public final class ServiceConfig {
    public static final String BOOTSTRAP = "148.113.190.78";
    public static final int PORT = 17301;

    private ServiceConfig() {}

    public static byte[] modulus() {
        return HexFormat.of()
                .parseHex(
                        "00e5a22d757626a24793eccf9574b33e3c8a77c2fd468a44ab335095be04336584d9f47a9728f07478b161d3a004fca4fd36da5f28d436c51bcd0baa515909fc2a3697941554747892e205cc165e53d7758175738a4350f22583b2f415aabe3810b5fc60d3958c257523355152c0fca6b6167864514af08b0a0017c76d69d54dff12b63cd7b3d83da7d13315dad870e6f4813a0c58a4d0a7e4d783a4dc2d3b3fa71528549a71c415e02428cf75626d1ef8a7bc654d44efbc249bd5e3ac1dbbba0d");
    }
}
