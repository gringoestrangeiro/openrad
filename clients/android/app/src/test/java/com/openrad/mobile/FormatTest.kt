package com.openrad.mobile

import com.openrad.mobile.model.Peer
import com.openrad.mobile.model.PeerSort
import com.openrad.mobile.model.PeerState
import com.openrad.mobile.util.filterAndSortPeers
import com.openrad.mobile.util.formatBytes
import com.openrad.mobile.util.validateConfigName
import com.openrad.mobile.util.validateNetworkName
import com.openrad.mobile.util.validateNetworkPassword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun bytes_binary_default() {
        assertEquals("512 B", formatBytes(512, false))
        assertEquals("1.0 KiB", formatBytes(1024, false))
        assertEquals("12.0 MiB", formatBytes(12_582_912, false))
    }

    @Test
    fun bytes_decimal_units() {
        assertEquals("1.0 kB", formatBytes(1000, true))
        assertEquals("12.6 MB", formatBytes(12_582_912, true))
    }

    @Test
    fun network_name_validation() {
        assertTrue(validateNetworkName("rede-demo"))
        assertFalse(validateNetworkName(""))
        assertFalse(validateNetworkName("a".repeat(256)))
        assertFalse(validateNetworkName("bad\u0007name"))
    }

    @Test
    fun network_password_validation() {
        assertTrue(validateNetworkPassword("secret1"))
        assertFalse(validateNetworkPassword("short"))
        assertFalse(validateNetworkPassword("a".repeat(257)))
    }

    @Test
    fun config_name_validation() {
        assertTrue(validateConfigName("fim-de-semana"))
        assertFalse(validateConfigName(""))
        assertFalse(validateConfigName("   "))
        assertFalse(validateConfigName("a".repeat(81)))
    }

    @Test
    fun peers_filter_and_sort_by_name() {
        val peers = listOf(
            Peer(2, "beta", "26.0.0.2", state = PeerState.CONNECTED),
            Peer(1, "alpha", "26.0.0.1", state = PeerState.OFFLINE),
            Peer(3, "gamma", "26.0.0.3", state = PeerState.FAILED),
        )
        val visible = filterAndSortPeers(peers, "", null, showOffline = false, PeerSort.NAME)
        assertEquals(listOf("beta", "gamma"), visible.map { it.name })

        val filtered = filterAndSortPeers(peers, "alp", null, showOffline = true, PeerSort.NAME)
        assertEquals(listOf("alpha"), filtered.map { it.name })
    }

    @Test
    fun peers_sort_by_status_connected_first() {
        val peers = listOf(
            Peer(1, "a", "26.0.0.1", state = PeerState.OFFLINE),
            Peer(2, "b", "26.0.0.2", state = PeerState.CONNECTED),
            Peer(3, "c", "26.0.0.3", state = PeerState.FAILED),
        )
        val sorted = filterAndSortPeers(peers, "", null, showOffline = true, PeerSort.STATUS)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.name })
    }
}
