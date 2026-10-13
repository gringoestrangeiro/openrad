package com.openrad.mobile.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.openrad.mobile.data.EngineRepository
import com.openrad.mobile.data.VpnRepository
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.ConnectionPhase
import com.openrad.mobile.model.JoinConfiguration
import com.openrad.mobile.model.JoinNetwork
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Root ViewModel. The real repository is the [EngineRepository]; tests inject another one. */
class OpenRadViewModel(val repo: VpnRepository) : ViewModel() {

    val snapshot = repo.snapshot
    val status = repo.status
    val activity = repo.activity
    val settings = repo.settings
    val favorites = repo.favorites
    val joinConfigurations = repo.joinConfigurations
    val publicResults = repo.publicResults
    val publicBusy = repo.publicBusy
    val publicHasMore = repo.publicHasMore
    val batchProgress = repo.batchProgress

    val selectedNetwork = MutableStateFlow<String?>(null)
    val peerFilter = MutableStateFlow("")
    val discoverQuery = MutableStateFlow("")

    init {
        // 300 ms public-search debounce (desktop contract); stale responses are discarded in the repository.
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            discoverQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { repo.searchPublic(it) }
        }
        // Refresh the catalog once the session comes online.
        viewModelScope.launch {
            repo.status.map { it.phase == ConnectionPhase.CONNECTED }.distinctUntilChanged().drop(1).filter { it }
                .collect { repo.searchPublic(discoverQuery.value) }
        }
    }

    fun connect() = viewModelScope.launch { repo.connect() }
    fun disconnect() = viewModelScope.launch { repo.disconnect() }
    fun retryPeers() = viewModelScope.launch { repo.retryPeers() }
    fun retryInterface() = viewModelScope.launch { repo.retryInterface() }
    fun testRtt(rid: Long) = viewModelScope.launch { repo.testRtt(rid) }
    fun toggleFavorite(name: String) = viewModelScope.launch { repo.toggleFavorite(name) }
    fun joinPublic(name: String) = viewModelScope.launch { repo.joinPublic(name) }
    fun loadMorePublic() = viewModelScope.launch { repo.loadMorePublic() }
    fun joinPrivate(name: String, password: CharArray) = viewModelScope.launch { repo.joinPrivate(name, password) }
    fun createPrivate(name: String, password: CharArray) = viewModelScope.launch { repo.createPrivate(name, password) }
    fun leaveNetwork(id: String) = viewModelScope.launch { repo.leaveNetwork(id) }
    fun deleteNetwork(id: String) = viewModelScope.launch { repo.deleteNetwork(id) }
    fun saveSettings(s: AppSettings) = viewModelScope.launch { repo.saveSettings(s) }
    fun saveJoinConfiguration(c: JoinConfiguration) = viewModelScope.launch { repo.saveJoinConfiguration(c) }
    fun deleteJoinConfiguration(name: String) = viewModelScope.launch { repo.deleteJoinConfiguration(name) }
    fun runBatchJoin(nets: List<JoinNetwork>, passwords: Map<String, CharArray>) =
        viewModelScope.launch { repo.runBatchJoin(nets, passwords) }
    fun resetIdentity() = viewModelScope.launch { repo.resetIdentity() }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L

        @Volatile private var shared: VpnRepository? = null

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { OpenRadViewModel(repository(application)) }
        }

        /** One repository per process so the UI can be recreated without losing activity. */
        fun repository(application: Application): VpnRepository =
            shared ?: synchronized(this) { shared ?: EngineRepository(application).also { shared = it } }
    }
}
