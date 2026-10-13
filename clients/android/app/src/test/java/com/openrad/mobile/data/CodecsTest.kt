package com.openrad.mobile.data

import com.openrad.mobile.engine.EngineState
import com.openrad.mobile.engine.Identity
import com.openrad.mobile.engine.Ip
import com.openrad.mobile.engine.LinkState
import com.openrad.mobile.engine.NetworkView
import com.openrad.mobile.engine.PeerView
import com.openrad.mobile.engine.TransportPath
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.ConnectionPhase
import com.openrad.mobile.model.JoinConfiguration
import com.openrad.mobile.model.JoinNetwork
import com.openrad.mobile.model.MemberRole
import com.openrad.mobile.model.PeerSort
import com.openrad.mobile.model.PeerState
import com.openrad.mobile.model.StartPage
import com.openrad.mobile.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsTest {
    @Test
    fun identityRoundTripsIncludingUnsignedRid() {
        val id = Identity(-2L, Ip.parse("26.1.2.3"), "phone", ByteArray(16) { 1 }, "synthetic-password".toByteArray(), "192.0.2.1")
        val text = Codecs.encodeIdentity(id)
        assertTrue(text.contains("18446744073709551614"))
        assertEquals(id, Codecs.decodeIdentity(text))
    }

    @Test
    fun settingsRoundTripAndClampRanges() {
        val s = AppSettings(
            language = "ru", reconnectAttempts = 7, reconnectBaseDelaySeconds = 9, peerSort = PeerSort.ADDRESS,
            startPage = StartPage.DISCOVER, forceRelay = true, outgoingBroadcasts = false, scale = 1.25f, recentActivityCount = 20,
        )
        assertEquals(s, Codecs.decodeSettings(Codecs.encodeSettings(s)))
        val wild = Codecs.decodeSettings(
            """{"reconnect_attempts":99,"reconnect_base_delay_seconds":0,"recent_activity_count":1,"scale":9,"language":"xx","peer_sort":"BOGUS"}""",
        )
        assertEquals(10, wild.reconnectAttempts)
        assertEquals(1, wild.reconnectBaseDelaySeconds)
        assertEquals(5, wild.recentActivityCount)
        assertEquals(1.5f, wild.scale)
        assertEquals("system", wild.language)
        assertEquals(PeerSort.NAME, wild.peerSort)
    }

    @Test
    fun configurationsPersistWithoutPasswordsAndRejectInvalidLists() {
        val configs = listOf(JoinConfiguration("weekend", listOf(JoinNetwork("lan", false), JoinNetwork("secret", true))))
        val text = Codecs.encodeConfigurations(configs)
        assertFalse(text.contains("password"))
        assertEquals(configs, Codecs.decodeConfigurations(text))
        assertThrows(Exception::class.java) { Codecs.decodeConfigurations("""[{"name":"","networks":[]}]""") }
        assertThrows(Exception::class.java) { Codecs.decodeConfigurations("""[{"name":"x","networks":[{"name":"a\nb"}]}]""") }
        val tooMany = (0..32).joinToString(",", "[", "]") { """{"name":"c$it","networks":[]}""" }
        assertThrows(Exception::class.java) { Codecs.decodeConfigurations(tooMany) }
    }

    @Test
    fun favoritesDropInvalidNames() {
        assertEquals(setOf("ok"), Codecs.decodeFavorites("""["ok",""," "]"""))
    }

    @Test
    fun snapshotMapperTranslatesEngineStateForTheUi() {
        val state = EngineState(
            phase = EngineState.Phase.ONLINE,
            rid = 5, vip = Ip.parse("26.0.0.5"), nodeName = "phone",
            networks = listOf(NetworkView("aa", "Alpha", 2, 3), NetworkView("bb", "Beta", 0, 1)),
            peers = listOf(
                PeerView(7, "desk", Ip.parse("26.0.0.7"), setOf("aa"), LinkState.CONNECTED, transport = TransportPath.RELAY, endpoint = "203.0.113.1:17302"),
                PeerView(8, "note", Ip.parse("26.0.0.8"), setOf("aa"), LinkState.REFUSED, detail = "refused"),
                PeerView(9, "away", Ip.parse("26.0.0.9"), setOf("aa"), LinkState.QUEUED),
            ),
        )
        val snap = SnapshotMapper.map(state, true, null, setOf("Alpha"), mapOf(7L to EngineRepository.RttResult.Millis(12.5), 8L to EngineRepository.RttResult.NoResponse))
        assertEquals("26.0.0.5", snap.vip)
        assertEquals(MemberRole.ADMIN, snap.networks[0].ownRole)
        assertTrue(snap.networks[0].isFavorite)
        assertEquals(MemberRole.PENDING, snap.networks[1].ownRole)
        assertTrue(snap.networks[1].pendingApproval)
        assertEquals(listOf(PeerState.CONNECTED, PeerState.REFUSED, PeerState.ONLINE_QUEUED), snap.peers.map { it.state })
        assertEquals(Transport.RELAY, snap.peers[0].transport)
        assertEquals(12.5, snap.peers[0].rttMs!!, 0.0)
        assertTrue(snap.peers[1].rttFailed)
        assertEquals(ConnectionPhase.CONNECTED, SnapshotMapper.status(state, true).phase)
        val failed = SnapshotMapper.status(EngineState(phase = EngineState.Phase.ERROR, error = "boom"), false)
        assertEquals(ConnectionPhase.ERROR, failed.phase)
        assertEquals("boom", failed.message)
        assertFalse(failed.connected)
    }
}
