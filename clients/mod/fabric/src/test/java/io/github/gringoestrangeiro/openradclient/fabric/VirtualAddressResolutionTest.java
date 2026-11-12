package io.github.gringoestrangeiro.openradclient.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualAddressResolutionTest {
    @Test
    void virtualLiteralRetainsItsNumericHostnameWithoutReverseDns() {
        var result = VirtualAddressResolution.resolve("26.115.53.148", 25565);
        assertTrue(result.isPresent());

        var address = result.orElseThrow();
        assertEquals("26.115.53.148", address.getHostName());
        assertEquals("26.115.53.148", address.getHostIp());
        assertEquals("26.115.53.148", address.asInetSocketAddress().getAddress().getHostName());
        assertEquals(25565, address.getPort());
    }

    @Test
    void doesNotResolveOtherAddressesOrInvalidLiterals() {
        assertFalse(VirtualAddressResolution.resolve("example.org", 25565).isPresent());
        assertFalse(VirtualAddressResolution.resolve("8.8.8.8", 25565).isPresent());
        assertFalse(VirtualAddressResolution.resolve("26.256.0.1", 25565).isPresent());
        assertFalse(VirtualAddressResolution.resolve("26.1.2.3.evil.example", 25565).isPresent());
        assertFalse(VirtualAddressResolution.resolve("::1", 25565).isPresent());
    }
}
