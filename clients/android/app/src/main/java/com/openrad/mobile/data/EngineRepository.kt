package com.openrad.mobile.data

import android.content.Context
import com.openrad.mobile.R
import com.openrad.mobile.engine.BlockDirection
import com.openrad.mobile.engine.CommandResult
import com.openrad.mobile.engine.Engine
import com.openrad.mobile.engine.EngineState
import com.openrad.mobile.engine.NetworkOperation
import com.openrad.mobile.model.ActivityEvent
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.BatchProgress
import com.openrad.mobile.model.ConnectionStatus
import com.openrad.mobile.model.JoinConfiguration
import com.openrad.mobile.model.JoinNetwork
import com.openrad.mobile.model.PublicNetwork
import com.openrad.mobile.model.Snapshot
import com.openrad.mobile.vpn.EngineHost
import com.openrad.mobile.vpn.OpenRadVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Real repository: talks to the Kotlin engine owned by [EngineHost] and the
 * foreground [OpenRadVpnService]. Preferences persist separately from the
 * encrypted identity; private passwords are zeroed and never stored.
 */
class EngineRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VpnRepository {
    private val app = context.applicationContext
    private val prefs = PreferencesStore(app)
    private val preferenceWrites = PreferenceTransactions()
    private val ids = AtomicLong(1)
    private val rtt = MutableStateFlow<Map<Long, RttResult>>(emptyMap())

    private val _settings = MutableStateFlow(prefs.settings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _favorites = MutableStateFlow(prefs.favorites())
    override val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _joinConfigurations = MutableStateFlow(prefs.configurations())
    override val joinConfigurations: StateFlow<List<JoinConfiguration>> = _joinConfigurations.asStateFlow()

    private val _activity = MutableStateFlow<List<ActivityEvent>>(emptyList())
    override val activity: StateFlow<List<ActivityEvent>> = _activity.asStateFlow()

    private val _batch = MutableStateFlow<BatchProgress?>(null)
    override val batchProgress: StateFlow<BatchProgress?> = _batch.asStateFlow()

    private data class SessionStamp(val engine: Engine, val generation: Long)

    private fun session(): SessionStamp? {
        val engine = EngineHost.engine ?: return null
        val generation = engine.activeSession ?: return null
        return SessionStamp(engine, generation).takeIf { EngineHost.engine === engine }
    }

    private val catalog = PublicCatalog<SessionStamp>()
    override val publicQuery = catalog.state.map { it.query }.stateIn(scope, SharingStarted.Eagerly, "")
    override val publicBusy = catalog.state.map { it.busy }.stateIn(scope, SharingStarted.Eagerly, false)
    override val publicHasMore = catalog.state.map { it.hasMore }.stateIn(scope, SharingStarted.Eagerly, false)
    private val batchGate = Mutex()

    /**
     * The engine cannot cancel a submitted search. The repository keeps the gate until its
     * callback/deadline, while a cancelled UI caller returns immediately. At most one search is
     * outstanding and superseded queries cannot fill the engine's bounded command queue.
     */
    private val searchGate = CommandGate(scope)

    override val snapshot: StateFlow<Snapshot> = combine(
        EngineHost.state, EngineHost.interfaceStatus, _favorites, rtt,
    ) { state, iface, favorites, rtt ->
        SnapshotMapper.map(state, iface.ready, iface.error, favorites, rtt)
    }.stateIn(scope, SharingStarted.Eagerly, Snapshot())

    override val status: StateFlow<ConnectionStatus> = EngineHost.state
        .map { SnapshotMapper.status(it, EngineHost.engine?.isRunning == true) }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionStatus())

    override val publicResults: StateFlow<List<PublicNetwork>> = combine(catalog.state, EngineHost.state, _favorites) { catalogState, state, favorites ->
        val joined = state.networks.map { it.name }.toSet()
        val visible = catalogState.results.takeIf { catalogState.session == session() } ?: emptyList()
        visible.map { (name, count) ->
            PublicNetwork(name, count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), name in joined, name in favorites)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            EngineHost.events.collect { push(it.message, it.error) }
        }
        scope.launch {
            EngineHost.state.map { Triple(it.phase, it.hostGeneration, it.sessionGeneration) }.distinctUntilChanged().collect {
                catalog.invalidate(session())
            }
        }
        if (prefs.hasCorruptValues()) push(app.getString(R.string.preferences_repaired), true)
    }

    private fun push(text: String, isError: Boolean = false) {
        _activity.update { (listOf(ActivityEvent(ids.getAndIncrement(), text, isError)) + it).take(40) }
    }

    private fun engine(): Engine? = EngineHost.engine?.takeIf { it.isRunning }

    private suspend fun await(
        expected: SessionStamp? = session(),
        block: (Engine, (CommandResult) -> Unit) -> Unit,
    ): CommandResult {
        val e = expected?.takeIf { it == session() }?.engine ?: return CommandResult.Failed(app.getString(R.string.error_not_connected))
        val deferred = CompletableDeferred<CommandResult>()
        block(e) { deferred.complete(it) }
        return deferred.await()
    }

    override suspend fun connect() {
        OpenRadVpnService.send(app, OpenRadVpnService.ACTION_CONNECT)
    }

    override suspend fun disconnect() {
        OpenRadVpnService.send(app, OpenRadVpnService.ACTION_DISCONNECT)
    }

    override suspend fun retryPeers() {
        val count = engine()?.retryPeers() ?: return push(app.getString(R.string.error_not_connected), true)
        push(app.getString(R.string.peer_retries_queued, count))
    }

    override suspend fun retryInterface() {
        if (engine() == null) return push(app.getString(R.string.error_not_connected), true)
        OpenRadVpnService.send(app, OpenRadVpnService.ACTION_RETRY_INTERFACE)
    }

    override suspend fun testRtt(rid: Long) {
        rtt.update { it + (rid to RttResult.Running) }
        val result = await { e, done -> e.ping(rid, done) }
        rtt.update {
            it + (rid to if (result is CommandResult.Rtt) RttResult.Millis(result.millis) else RttResult.NoResponse)
        }
    }

    override suspend fun searchPublic(query: String) {
        val request = catalog.begin(session(), query) ?: return
        runSearch(request)
    }

    override suspend fun loadMorePublic() {
        val request = catalog.more(session()) ?: return
        runSearch(request)
    }

    private suspend fun runSearch(request: PublicCatalog.Request<SessionStamp>) {
        var result: CommandResult? = null
        try {
            result = searchGate.await({ catalog.isCurrent(request, session()) }) {
                await(request.session) { e, done ->
                    e.searchInSession(request.session.generation, request.query, request.cursor, done)
                }
            }
        } finally {
            val current = session()
            val accepted = catalog.complete(request, current, result)
            if (accepted && result is CommandResult.Failed) {
                push(app.getString(R.string.search_failed, result.message), true)
            }
        }
    }

    private fun reportJoin(name: String, result: CommandResult): JoinOutcome {
        return when (result) {
            is CommandResult.Network -> when (val o = result.outcome) {
                is NetworkOperation.Outcome.Joined -> if (o.pendingApproval) {
                    push(app.getString(R.string.join_pending, name))
                    JoinOutcome.PENDING
                } else {
                    push(app.getString(R.string.join_done, name))
                    JoinOutcome.JOINED
                }
                is NetworkOperation.Outcome.Created -> {
                    push(app.getString(R.string.create_done, name))
                    JoinOutcome.JOINED
                }
                is NetworkOperation.Outcome.Removed -> JoinOutcome.JOINED
            }
            is CommandResult.Failed -> {
                push(app.getString(R.string.join_failed, name, result.message), true)
                JoinOutcome.FAILED
            }
            else -> JoinOutcome.FAILED
        }
    }

    private enum class JoinOutcome { JOINED, PENDING, FAILED }

    override suspend fun joinPublic(name: String) {
        reportJoin(name, await { e, done -> e.join(name, null, done) })
    }

    override suspend fun joinPrivate(name: String, password: CharArray) {
        try {
            reportJoin(name, await { e, done -> e.join(name, password.copyOf(), done) })
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun createPrivate(name: String, password: CharArray) {
        try {
            reportJoin(name, await { e, done -> e.create(name, password.copyOf(), done) })
        } finally {
            password.fill('\u0000')
        }
    }

    override suspend fun leaveNetwork(networkId: String) {
        val name = EngineHost.state.value.networks.firstOrNull { it.networkId == networkId }?.name ?: networkId
        when (val r = await { e, done -> e.leave(networkId, done) }) {
            is CommandResult.Failed -> push(app.getString(R.string.leave_failed, name, r.message), true)
            else -> push(app.getString(R.string.leave_done, name))
        }
    }

    override suspend fun deleteNetwork(networkId: String) {
        val name = EngineHost.state.value.networks.firstOrNull { it.networkId == networkId }?.name ?: networkId
        when (val r = await { e, done -> e.delete(networkId, done) }) {
            is CommandResult.Failed -> push(app.getString(R.string.leave_failed, name, r.message), true)
            else -> push(app.getString(R.string.delete_done, name))
        }
    }

    override suspend fun toggleFavorite(name: String) {
        preferenceWrites.update(_favorites, prefs::saveFavorites,
            { if (name in it) it - name else it + name })
            ?: push(app.getString(R.string.preferences_save_failed), true)
    }

    override suspend fun saveSettings(next: AppSettings) {
        val change = preferenceWrites.update(_settings, prefs::saveSettings,
            { current -> settingsFromDraft(next, current) },
            { EngineHost.updateConfig(OpenRadVpnService.engineConfig(it)) })
            ?: return push(app.getString(R.string.preferences_save_failed), true)
        val previous = change.previous
        val normalized = change.current
        push(app.getString(R.string.settings_saved))
        if (previous.outgoingBroadcasts != normalized.outgoingBroadcasts && engine() != null) {
            // Routes are fixed when the TUN is established; rebuild it with the new LAN routes.
            OpenRadVpnService.send(app, OpenRadVpnService.ACTION_RETRY_INTERFACE)
        }
        if (previous.nodeName != normalized.nodeName && engine() != null) {
            // Same as the daemon's Rename: keep the identity, reconnect with the new name.
            push(app.getString(R.string.rename_reconnecting))
            // The service restarts the engine itself, so its watcher cannot tear down the new one.
            OpenRadVpnService.send(app, OpenRadVpnService.ACTION_RECONNECT)
        }
    }

    override suspend fun setStealthBlock(address: Int, direction: BlockDirection?) {
        val text = com.openrad.mobile.engine.Ip.format(address)
        if (!BlockDirection.validAddress(address)) {
            push(app.getString(R.string.block_invalid), true)
            return
        }
        var limited = false
        val change = preferenceWrites.update(_settings, prefs::saveSettings, { current ->
            if (direction != null && address !in current.blocked && current.blocked.size >= BlockDirection.MAX_ADDRESSES) {
                limited = true
                null
            } else current.copy(
                blocked = if (direction == null) current.blocked - address else current.blocked + (address to direction),
            )
        }, {
            // Apply live settings in write order, without running network commands under the gate.
            EngineHost.updateConfig(OpenRadVpnService.engineConfig(it))
        })
        if (change == null) {
            if (limited) push(app.getString(R.string.block_limit, BlockDirection.MAX_ADDRESSES), true)
            else push(app.getString(R.string.preferences_save_failed), true)
            return
        }
        push(app.getString(if (direction == null) R.string.block_removed else R.string.block_added, text))
    }

    override suspend fun saveJoinConfiguration(config: JoinConfiguration) {
        val trimmed = config.copy(networks = config.networks.distinctBy { it.name }.take(JoinConfiguration.MAX_NETWORKS))
        var limited = false
        val change = preferenceWrites.update(_joinConfigurations, prefs::saveConfigurations, { current ->
            if (current.none { it.name == trimmed.name } && current.size >= JoinConfiguration.MAX_CONFIGURATIONS) {
                limited = true
                null
            } else current.filterNot { it.name == trimmed.name } + trimmed
        })
        if (change == null) {
            if (limited) push(app.getString(R.string.config_limit, JoinConfiguration.MAX_CONFIGURATIONS), true)
            else push(app.getString(R.string.preferences_save_failed), true)
            return
        }
        push(app.getString(R.string.config_saved, trimmed.name))
    }

    override suspend fun deleteJoinConfiguration(name: String) {
        preferenceWrites.update(_joinConfigurations, prefs::saveConfigurations,
            { cur -> cur.filterNot { it.name == name } })
            ?: push(app.getString(R.string.preferences_save_failed), true)
    }

    override suspend fun runBatchJoin(networks: List<JoinNetwork>, passwords: Map<String, CharArray>) {
        if (!batchGate.tryLock()) {
            passwords.values.forEach { it.fill('\u0000') }
            return
        }
        var started = false
        try {
            val session = session() ?: return push(app.getString(R.string.error_not_connected), true)
            val unique = networks.distinctBy { it.name }.take(JoinConfiguration.MAX_NETWORKS)
            val joined = session.engine.snapshot().networks.map { it.name }.toSet()
            _batch.value = BatchProgress(total = unique.size)
            started = true
            coroutineScope {
                val jobs = unique.mapIndexed { index, net ->
                    if (index > 0) delay(BATCH_SPACING_MS)
                    if (net.name in joined) {
                        _batch.update { it?.copy(skipped = it.skipped + 1) }
                        return@mapIndexed null
                    }
                    async {
                        val result = if (net.private && passwords[net.name] == null) {
                            CommandResult.Failed(app.getString(R.string.password_invalid))
                        } else {
                            await(session) { e, done ->
                                val password = if (net.private) passwords.getValue(net.name).copyOf() else null
                                e.joinInSession(session.generation, net.name, password, done)
                            }
                        }
                        val outcome = reportJoin(net.name, result)
                        _batch.update { progress ->
                            progress?.let {
                                when (outcome) {
                                    JoinOutcome.JOINED -> it.copy(joined = it.joined + 1)
                                    JoinOutcome.PENDING -> it.copy(pendingApproval = it.pendingApproval + 1)
                                    JoinOutcome.FAILED -> it.copy(failed = it.failed + 1)
                                }
                            }
                        }
                    }
                }
                jobs.filterNotNull().awaitAll()
            }
            _batch.value?.let { progress ->
                push(app.getString(R.string.batch_summary, progress.joined, progress.skipped, progress.pendingApproval, progress.failed))
            }
        } finally {
            if (started) _batch.update { it?.copy(running = false) }
            passwords.values.forEach { it.fill('\u0000') }
            batchGate.unlock()
        }
    }

    override suspend fun resetIdentity() {
        val cleared = try {
            withContext(Dispatchers.IO) { EngineHost.resetIdentity(app) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!cleared) return push(app.getString(R.string.preferences_save_failed), true)
        OpenRadVpnService.send(app, OpenRadVpnService.ACTION_DISCONNECT)
        rtt.value = emptyMap()
        push(app.getString(R.string.identity_reset_done))
    }

    sealed class RttResult {
        data object Running : RttResult()
        data object NoResponse : RttResult()
        data class Millis(val value: Double) : RttResult()
    }

    companion object {
        const val BATCH_SPACING_MS = 50L
    }
}

/** Read the block list inside the transaction: an older settings draft must not overwrite it. */
internal fun settingsFromDraft(draft: AppSettings, current: AppSettings): AppSettings =
    draft.copy(blocked = current.blocked).normalized()

/** Pure mapping from [EngineState] to the UI model (unit-tested). */
object SnapshotMapper {
    fun map(
        state: EngineState,
        interfaceReady: Boolean,
        interfaceError: String?,
        favorites: Set<String>,
        rtt: Map<Long, EngineRepository.RttResult>,
    ): Snapshot = Snapshot(
        vip = state.vip?.let { com.openrad.mobile.engine.Ip.format(it) },
        networks = state.networks.map { n ->
            com.openrad.mobile.model.NetworkInfo(
                name = n.name,
                networkId = n.networkId,
                isFavorite = n.name in favorites,
                memberCount = n.memberCount,
                ownRole = when (n.ownRole) {
                    2L -> com.openrad.mobile.model.MemberRole.ADMIN
                    1L -> com.openrad.mobile.model.MemberRole.MEMBER
                    0L -> com.openrad.mobile.model.MemberRole.PENDING
                    else -> com.openrad.mobile.model.MemberRole.UNKNOWN
                },
                pendingApproval = n.ownRole == 0L,
            )
        },
        peers = state.peers.map { p ->
            val r = rtt[p.rid]
            com.openrad.mobile.model.Peer(
                rid = p.rid,
                name = p.name,
                vip = com.openrad.mobile.engine.Ip.format(p.vip),
                networkIds = p.networkIds,
                state = when (p.link) {
                    com.openrad.mobile.engine.LinkState.CONNECTED -> com.openrad.mobile.model.PeerState.CONNECTED
                    com.openrad.mobile.engine.LinkState.CONNECTING -> com.openrad.mobile.model.PeerState.CONNECTING
                    com.openrad.mobile.engine.LinkState.FAILED -> com.openrad.mobile.model.PeerState.FAILED
                    com.openrad.mobile.engine.LinkState.REFUSED -> com.openrad.mobile.model.PeerState.REFUSED
                    com.openrad.mobile.engine.LinkState.UNAVAILABLE -> com.openrad.mobile.model.PeerState.UNAVAILABLE
                    com.openrad.mobile.engine.LinkState.OFFLINE -> com.openrad.mobile.model.PeerState.OFFLINE
                    com.openrad.mobile.engine.LinkState.QUEUED, com.openrad.mobile.engine.LinkState.IDLE ->
                        com.openrad.mobile.model.PeerState.ONLINE_QUEUED
                },
                detail = p.detail,
                transport = when (p.transport) {
                    com.openrad.mobile.engine.TransportPath.DIRECT_TCP -> com.openrad.mobile.model.Transport.DIRECT_TCP
                    com.openrad.mobile.engine.TransportPath.DIRECT_UDP -> com.openrad.mobile.model.Transport.DIRECT_UDP
                    com.openrad.mobile.engine.TransportPath.RELAY -> com.openrad.mobile.model.Transport.RELAY
                    null -> null
                },
                endpoint = p.endpoint,
                rttMs = (r as? EngineRepository.RttResult.Millis)?.value,
                rttFailed = r is EngineRepository.RttResult.NoResponse,
                rttRunning = r is EngineRepository.RttResult.Running,
            )
        },
        interfaceReady = interfaceReady,
        interfaceError = interfaceError,
        traffic = com.openrad.mobile.model.Traffic(
            state.traffic.sentBytes, state.traffic.receivedBytes, state.traffic.sentFrames,
            state.traffic.receivedFrames, state.traffic.dropped,
        ),
        latencyMs = state.latencyMs.toInt(),
        retryQueued = state.retryQueued,
        retryActive = state.retryActive,
        forceRelay = state.forceRelay,
        rid = state.rid,
        nodeName = state.nodeName,
        pendingIncoming = state.pendingIncoming,
    )

    fun status(state: EngineState, running: Boolean): ConnectionStatus = when (state.phase) {
        EngineState.Phase.STOPPED -> ConnectionStatus(com.openrad.mobile.model.ConnectionPhase.IDLE, "", connected = false)
        EngineState.Phase.PROVISIONING -> ConnectionStatus(com.openrad.mobile.model.ConnectionPhase.PROVISIONING, "", connected = running)
        EngineState.Phase.CONNECTING, EngineState.Phase.RECONNECTING ->
            ConnectionStatus(com.openrad.mobile.model.ConnectionPhase.CONNECTING, state.error.orEmpty(), connected = running)
        EngineState.Phase.ONLINE -> ConnectionStatus(com.openrad.mobile.model.ConnectionPhase.CONNECTED, "", connected = true)
        EngineState.Phase.ERROR -> ConnectionStatus(com.openrad.mobile.model.ConnectionPhase.ERROR, state.error.orEmpty(), connected = false)
    }
}
