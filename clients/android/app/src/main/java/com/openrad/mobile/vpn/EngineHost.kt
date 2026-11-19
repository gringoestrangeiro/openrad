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

    internal data class Attachment(val engine: Engine, val session: Long)
    private data class Sink(val engineGeneration: Long, val attachment: Attachment, val write: (ByteArray) -> Unit)

    @Volatile private var sink: Sink? = null
    val packetSink: ((ByteArray) -> Unit)? get() = sink?.write

    private val transitions = Any()
    private var identityStore: IdentityStore? = null
    private var networkPolicy: WifiNetworkPolicy? = null
    private var serviceGeneration = 0L
    private var serviceAlive = false
    private var engineOwner = 0L
    @Volatile private var engineGeneration = 0L

    internal fun registerService(): Long = synchronized(this) {
        clearInterface(null)
        serviceAlive = true
        ++serviceGeneration
    }

    internal fun retireService(generation: Long): Engine? = synchronized(this) {
        if (serviceGeneration != generation) return@synchronized null
        serviceAlive = false
        clearInterface(null)
        engine?.takeIf { engineOwner == generation }
    }

    internal fun ownsService(generation: Long): Boolean = synchronized(this) {
        serviceAlive && serviceGeneration == generation
    }

    private fun listenerFor(generation: Long) = object : EngineListener {
        override fun onState(state: EngineState) {
            synchronized(this@EngineHost) {
                if (engineGeneration == generation) _state.value = state.copy(hostGeneration = generation)
            }
        }

        override fun onEvent(message: String, error: Boolean) {
            if (engineGeneration == generation) _events.tryEmit(Event(message, error))
        }

        override fun onPacket(packet: ByteArray, session: Long) {
            val target = sink ?: return
            if (target.engineGeneration == generation && target.attachment.session == session) target.write(packet)
        }
    }

    fun identityStore(context: Context): IdentityStore = synchronized(this) {
        identityStore ?: EncryptedIdentityStore(context.applicationContext).also { identityStore = it }
    }

    fun start(context: Context, config: EngineConfig, protector: SocketProtector?): Engine =
        start(context, { config }, protector)

    fun start(context: Context, readConfig: () -> EngineConfig, protector: SocketProtector?,
              owner: Long = synchronized(this) { serviceGeneration }): Engine = synchronized(transitions) {
        val existing = synchronized(this) {
            check(ownsService(owner)) { "VPN service was destroyed" }
            engine?.takeIf { it.isRunning && engineOwner == owner }?.let {
                it.updateConfig(readConfig())
                return it
            }
            engine
        }
        check(existing == null || stopEngine(existing)) { "Previous VPN engine is still shutting down" }
        synchronized(this) {
            check(ownsService(owner)) { "VPN service was destroyed" }
            val config = readConfig()
            val generation = ++engineGeneration
            val policy = WifiNetworkPolicy(context).also { networkPolicy = it }
            val e = Engine(config, identityStore(context), listenerFor(generation), protector, policy)
            engine = e
            engineOwner = owner
            _state.value = EngineState(hostGeneration = generation)
            policy.start { if (engineGeneration == generation && engine === e) e.networkChanged() }
            e.start()
            e
        }
    }

    fun stop(): Boolean = synchronized(transitions) {
        val e = synchronized(this) { engine } ?: return@synchronized true
        stopEngine(e)
    }

    internal fun stopForService(generation: Long): Boolean = synchronized(transitions) {
        val e = synchronized(this) { engine.takeIf { ownsService(generation) } } ?: return@synchronized true
        stopEngine(e)
    }

    internal fun stopAfterServiceDestroyed(generation: Long, expected: Engine) {
        synchronized(transitions) {
            if (synchronized(this) { engine === expected && engineOwner == generation }) stopEngine(expected)
        }
    }

    private fun stopEngine(expected: Engine): Boolean {
        synchronized(this) { if (engine === expected) clearInterface(null) }
        return try {
            expected.stop()
        } finally {
            synchronized(this) {
                if (engine === expected) {
                    networkPolicy?.stop()
                    networkPolicy = null
                    _state.value = expected.snapshot().copy(hostGeneration = engineGeneration)
                }
            }
        }
    }

    internal fun resetIdentity(context: Context): Boolean = synchronized(transitions) {
        if (!stop()) return@synchronized false
        identityStore(context).clear()
        true
    }

    fun updateConfig(config: EngineConfig) = updateConfig { config }

    fun updateConfig(config: () -> EngineConfig) {
        synchronized(this) { engine?.takeIf { it.isRunning }?.updateConfig(config()) }
    }

    internal fun attachment(generation: Long): Attachment? = synchronized(this) {
        if (!ownsService(generation) || engineOwner != generation) return@synchronized null
        val e = engine ?: return@synchronized null
        val session = e.activeSession ?: return@synchronized null
        Attachment(e, session)
    }

    internal fun attachInterface(generation: Long, attachment: Attachment, write: (ByteArray) -> Unit): Boolean = synchronized(this) {
        if (this.attachment(generation) != attachment) return@synchronized false
        sink = Sink(engineGeneration, attachment, write)
        attachment.engine.tunAttached = true
        _interface.value = InterfaceStatus(true)
        true
    }

    internal fun detachInterface(generation: Long, error: String? = null) = synchronized(this) {
        if (serviceGeneration == generation) clearInterface(error)
    }

    private fun clearInterface(error: String?) {
        sink = null
        engine?.tunAttached = false
        _interface.value = InterfaceStatus(false, error)
    }

    internal fun setInterface(ready: Boolean, error: String?) = synchronized(this) {
        if (!ready) clearInterface(error)
        else {
            engine?.tunAttached = true
            _interface.value = InterfaceStatus(true, error)
        }
    }

    fun emit(message: String, error: Boolean = false) {
        _events.tryEmit(Event(message, error))
    }
}
