package com.openrad.mobile.data

import com.openrad.mobile.model.ActivityEvent
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.BatchProgress
import com.openrad.mobile.model.ConnectionStatus
import com.openrad.mobile.model.JoinConfiguration
import com.openrad.mobile.model.JoinNetwork
import com.openrad.mobile.model.PublicNetwork
import com.openrad.mobile.model.Snapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the UI and the VPN engine.
 *
 * [EngineRepository] connects the UI to the real Kotlin engine (via `EngineHost`
 * and `OpenRadVpnService`); [MockRepository] remains available for previews and
 * synthetic tests. The UI only talks to this interface.
 */
interface VpnRepository {
    val snapshot: StateFlow<Snapshot>
    val status: StateFlow<ConnectionStatus>
    val activity: StateFlow<List<ActivityEvent>>
    val settings: StateFlow<AppSettings>
    val favorites: StateFlow<Set<String>>
    val joinConfigurations: StateFlow<List<JoinConfiguration>>
    val batchProgress: StateFlow<BatchProgress?>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun retryPeers()
    suspend fun retryInterface()
    suspend fun testRtt(rid: Long)
    suspend fun searchPublic(query: String)
    suspend fun loadMorePublic()
    suspend fun joinPublic(name: String)
    /** The password array is zeroed by the repository after use. */
    suspend fun joinPrivate(name: String, password: CharArray)
    suspend fun createPrivate(name: String, password: CharArray)
    suspend fun leaveNetwork(networkId: String)
    suspend fun deleteNetwork(networkId: String)
    suspend fun toggleFavorite(name: String)
    suspend fun saveSettings(next: AppSettings)
    suspend fun saveJoinConfiguration(config: JoinConfiguration)
    suspend fun deleteJoinConfiguration(name: String)
    /** Passwords live only in memory for the duration of the batch and are zeroed afterwards. */
    suspend fun runBatchJoin(networks: List<JoinNetwork>, passwords: Map<String, CharArray>)
    suspend fun resetIdentity()

    val publicResults: StateFlow<List<PublicNetwork>>
    val publicQuery: StateFlow<String>
    val publicBusy: StateFlow<Boolean>
    val publicHasMore: StateFlow<Boolean>
}
