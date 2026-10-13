package com.openrad.mobile.data

import com.openrad.mobile.model.ActivityEvent
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.BatchProgress
import com.openrad.mobile.model.ConnectionPhase
import com.openrad.mobile.model.ConnectionStatus
import com.openrad.mobile.model.JoinConfiguration
import com.openrad.mobile.model.JoinNetwork
import com.openrad.mobile.model.MemberRole
import com.openrad.mobile.model.NetworkInfo
import com.openrad.mobile.model.Peer
import com.openrad.mobile.model.PeerState
import com.openrad.mobile.model.PublicNetwork
import com.openrad.mobile.model.Snapshot
import com.openrad.mobile.model.Traffic
import com.openrad.mobile.model.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Synthetic data for the phase-1 UI.
 * Same idea as the desktop synthetic screenshots (`docs/screenshots/`) and
 * the fixtures in the Rust repo's `tests/fixtures/`: no real secrets.
 */
class MockRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VpnRepository {

    private val ids = AtomicLong(1)
    private val rng = Random(42)

    private val netAlpha = NetworkInfo(
        name = "rede-demo",
        networkId = "aa01",
        isFavorite = true,
        memberCount = 4,
        ownRole = MemberRole.ADMIN,
    )
    private val netBeta = NetworkInfo(
        name = "jogos-retro",
        networkId = "bb02",
        isFavorite = false,
        memberCount = 9,
        ownRole = MemberRole.MEMBER,
    )

    private val _snapshot = MutableStateFlow(
        Snapshot(
            vip = "26.12.34.56",
            networks = listOf(netAlpha, netBeta),
            peers = listOf(
                Peer(101, "desktop-sala", "26.12.34.10", setOf("aa01"), PeerState.CONNECTED, transport = Transport.DIRECT_TCP, endpoint = "203.0.113.10:4899", rttMs = 23.4),
                Peer(102, "notebook", "26.12.34.11", setOf("aa01", "bb02"), PeerState.CONNECTED, transport = Transport.DIRECT_UDP, endpoint = "203.0.113.11:5123", rttMs = 41.0),
                Peer(103, "rpi-quarto", "26.12.34.12", setOf("bb02"), PeerState.CONNECTING),
                Peer(104, "amigo-sp", "26.12.34.13", setOf("bb02"), PeerState.FAILED, detail = "connection refused"),
                Peer(105, "primo-mg", "26.12.34.14", setOf("bb02"), PeerState.OFFLINE),
            ),
            interfaceReady = true,
            traffic = Traffic(sentBytes = 12_582_912, receivedBytes = 41_234_111, sentFrames = 91234, receivedFrames = 120456, dropped = 12),
            latencyMs = 18,
        ),
    )
    override val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _status = MutableStateFlow(
        ConnectionStatus(ConnectionPhase.CONNECTED, "connected", connected = true),
    )
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _activity = MutableStateFlow(
        listOf(
            ActivityEvent(1, "desktop-sala: connected · direct TCP"),
            ActivityEvent(2, "jogos-retro: amigo-sp refused the connection", isError = true),
            ActivityEvent(3, "public catalog: 12 networks"),
        ),
    )
    override val activity: StateFlow<List<ActivityEvent>> = _activity.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _favorites = MutableStateFlow(setOf("rede-demo"))
    override val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _joinConfigurations = MutableStateFlow(
        listOf(
            JoinConfiguration("fim-de-semana", listOf(JoinNetwork("rede-demo", false), JoinNetwork("jogos-retro", true))),
        ),
    )
    override val joinConfigurations: StateFlow<List<JoinConfiguration>> = _joinConfigurations.asStateFlow()

    private val _publicResults = MutableStateFlow(
        listOf(
            PublicNetwork("retro-games-br", 128, joined = false, isFavorite = false),
            PublicNetwork("minecraft-familia", 64, joined = false),
            PublicNetwork("rede-demo", 4, joined = true),
            PublicNetwork("linux-gamers", 512, joined = false, isFavorite = true),
        ),
    )
    override val publicResults: StateFlow<List<PublicNetwork>> = _publicResults.asStateFlow()

    private val _publicQuery = MutableStateFlow("")
    override val publicQuery: StateFlow<String> = _publicQuery.asStateFlow()

    private val _publicBusy = MutableStateFlow(false)
    override val publicBusy: StateFlow<Boolean> = _publicBusy.asStateFlow()

    override val publicHasMore: StateFlow<Boolean> = MutableStateFlow(false)

    private val _batch = MutableStateFlow<BatchProgress?>(null)
    override val batchProgress: StateFlow<BatchProgress?> = _batch.asStateFlow()

    private fun pushActivity(text: String, isError: Boolean = false) {
        // Keep at most 40 events (like the Rust recent_activity_count max).
        _activity.update { (listOf(ActivityEvent(ids.getAndIncrement(), text, isError)) + it).take(40) }
    }

    override suspend fun connect() {
        _status.value = ConnectionStatus(ConnectionPhase.CONNECTING, "connecting")
        delay(900)
        _status.value = ConnectionStatus(ConnectionPhase.CONNECTED, "connected", connected = true)
        _snapshot.update { it.copy(interfaceReady = true, interfaceError = null) }
        pushActivity("connected")
    }

    override suspend fun disconnect() {
        _status.value = ConnectionStatus(ConnectionPhase.DISCONNECTING, "disconnecting")
        delay(600)
        _status.value = ConnectionStatus(ConnectionPhase.IDLE, "disconnected")
        pushActivity("disconnected · interface removed")
    }

    override suspend fun retryPeers() {
        pushActivity("retrying failed peers…")
        delay(400)
        _snapshot.update { snap ->
            snap.copy(peers = snap.peers.map { if (it.state == PeerState.FAILED) it.copy(state = PeerState.CONNECTING) else it })
        }
    }

    override suspend fun retryInterface() {
        _snapshot.update { it.copy(interfaceError = null, interfaceReady = true) }
        pushActivity("interface setup retried")
    }

    override suspend fun testRtt(rid: Long) {
        _snapshot.update { snap ->
            snap.copy(peers = snap.peers.map { if (it.rid == rid) it.copy(rttMs = null, rttFailed = false) else it })
        }
        delay(700)
        val ok = rng.nextBoolean()
        _snapshot.update { snap ->
            snap.copy(
                peers = snap.peers.map {
                    if (it.rid == rid) {
                        if (ok) it.copy(rttMs = 15 + rng.nextDouble() * 80, rttFailed = false)
                        else it.copy(rttMs = null, rttFailed = true)
                    } else it
                },
            )
        }
    }

    override suspend fun searchPublic(query: String) {
        // The 300 ms debounce happens in the UI; here we only filter the mock.
        _publicQuery.value = query
        _publicBusy.value = true
        delay(350)
        val q = query.trim().lowercase()
        val all = listOf(
            PublicNetwork("retro-games-br", 128),
            PublicNetwork("minecraft-familia", 64),
            PublicNetwork("rede-demo", 4, joined = true),
            PublicNetwork("linux-gamers", 512, isFavorite = true),
            PublicNetwork("cs-classico", 256),
            PublicNetwork("lan-party-sp", 32),
        )
        _publicResults.value = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
        _publicBusy.value = false
    }

    override suspend fun loadMorePublic() {
        delay(300)
        pushActivity("loaded more networks")
    }

    override suspend fun joinPublic(name: String) {
        delay(500)
        pushActivity("$name: joined")
    }

    override suspend fun joinPrivate(name: String, password: CharArray) {
        password.fill('\u0000')
        delay(500)
        // Passwords are never logged nor persisted (Rust contract).
        pushActivity("$name: join requested")
    }

    override suspend fun createPrivate(name: String, password: CharArray) {
        password.fill('\u0000')
        delay(500)
        pushActivity("$name: network created")
    }

    override suspend fun leaveNetwork(networkId: String) {
        _snapshot.update { snap -> snap.copy(networks = snap.networks.filterNot { it.networkId == networkId }) }
        pushActivity("left network")
    }

    override suspend fun deleteNetwork(networkId: String) {
        _snapshot.update { snap -> snap.copy(networks = snap.networks.filterNot { it.networkId == networkId }) }
        pushActivity("network deleted for everyone")
    }

    override suspend fun toggleFavorite(name: String) {
        _favorites.update { if (name in it) it - name else it + name }
        _snapshot.update { snap ->
            snap.copy(networks = snap.networks.map { if (it.name == name) it.copy(isFavorite = name !in favorites.value) else it })
        }
    }

    override suspend fun saveSettings(next: AppSettings) {
        _settings.value = next.copy(
            reconnectAttempts = next.reconnectAttempts.coerceIn(1, 10),
            reconnectBaseDelaySeconds = next.reconnectBaseDelaySeconds.coerceIn(1, 30),
            recentActivityCount = next.recentActivityCount.coerceIn(5, 40),
        )
        _snapshot.update { it.copy(forceRelay = next.forceRelay) }
        pushActivity("settings saved")
    }

    override suspend fun saveJoinConfiguration(config: JoinConfiguration) {
        _joinConfigurations.update { cur -> (cur.filterNot { it.name == config.name } + config).take(32) }
        pushActivity("join configuration saved: ${config.name}")
    }

    override suspend fun deleteJoinConfiguration(name: String) {
        _joinConfigurations.update { cur -> cur.filterNot { it.name == name } }
    }

    override suspend fun runBatchJoin(networks: List<JoinNetwork>, passwords: Map<String, CharArray>) {
        // Overlapping joins spaced >= 50 ms apart (Rust contract). Passwords live in RAM only.
        val nets = networks.take(128)
        _batch.value = BatchProgress(nets.size)
        for (net in nets) {
            delay(60)
            pushActivity("${net.name}: join requested")
            _batch.value = _batch.value?.let { it.copy(joined = it.joined + 1) }
        }
        _batch.value = _batch.value?.copy(running = false)
        passwords.values.forEach { it.fill('\u0000') }
    }

    override suspend fun resetIdentity() {
        pushActivity("identity reset")
    }
}
