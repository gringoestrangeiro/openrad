package com.openrad.mobile.vpn

import android.content.Context
import com.openrad.mobile.data.EncryptedIdentityStore
import com.openrad.mobile.engine.Engine
import com.openrad.mobile.engine.EngineConfig
import com.openrad.mobile.engine.EngineListener
import com.openrad.mobile.engine.EngineState
import com.openrad.mobile.engine.IdentityStore
import com.openrad.mobile.engine.SocketProtector
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the single engine (the Android counterpart of the
 * per-user daemon). The foreground [OpenRadVpnService] starts and stops it; the
 * UI only observes it through the repository, so closing the UI keeps the
 * session running and Disconnect explicitly stops it.
 */
object EngineHost {
    data class InterfaceStatus(val ready: Boolean = false, val error: String? = null)
    data class Event(val message: String, val error: Boolean)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _interface = MutableStateFlow(InterfaceStatus())
    val interfaceStatus: StateFlow<InterfaceStatus> = _interface.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    var engine: Engine? = null
        private set

    /** Writes an inbound IPv4 packet to the TUN; null while no TUN is attached. */
    @Volatile
    var packetSink: ((ByteArray) -> Unit)? = null

    private var identityStore: IdentityStore? = null
    private var networkPolicy: WifiNetworkPolicy? = null

    private val listener = object : EngineListener {
        override fun onState(state: EngineState) {
            _state.value = state
        }

        override fun onEvent(message: String, error: Boolean) {
            _events.tryEmit(Event(message, error))
        }

        override fun onPacket(packet: ByteArray) {
            packetSink?.invoke(packet)
        }
    }

    fun identityStore(context: Context): IdentityStore = synchronized(this) {
        identityStore ?: EncryptedIdentityStore(context.applicationContext).also { identityStore = it }
    }

    /**
     * Starts (or reconfigures) the engine. Blocking-free; safe from any thread.
     * Direct peer paths follow [WifiNetworkPolicy]: network changes are pushed
     * to the engine, which retires direct channels when Wi-Fi is lost.
     */
    fun start(context: Context, config: EngineConfig, protector: SocketProtector?): Engine = synchronized(this) {
        engine?.takeIf { it.isRunning }?.let {
            it.updateConfig(config)
            return it
        }
        networkPolicy?.stop()
        val policy = WifiNetworkPolicy(context).also { networkPolicy = it }
        policy.start { engine?.networkChanged() }
        val e = Engine(config, identityStore(context), listener, protector, policy)
        engine = e
        e.start()
        e
    }

    /** Stops the engine and waits for its threads; call off the main thread. */
    fun stop() {
        val e = synchronized(this) { engine } ?: return
        e.stop()
        synchronized(this) {
            networkPolicy?.stop()
            networkPolicy = null
        }
        _state.value = e.snapshot()
    }

    fun updateConfig(config: EngineConfig) {
        engine?.takeIf { it.isRunning }?.updateConfig(config)
    }

    fun setInterface(ready: Boolean, error: String?) {
        _interface.value = InterfaceStatus(ready, error)
        engine?.tunAttached = ready
    }

    fun emit(message: String, error: Boolean = false) {
        _events.tryEmit(Event(message, error))
    }
}
