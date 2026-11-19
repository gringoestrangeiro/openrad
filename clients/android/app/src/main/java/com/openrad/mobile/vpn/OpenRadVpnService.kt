package com.openrad.mobile.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openrad.mobile.MainActivity
import com.openrad.mobile.R
import com.openrad.mobile.data.PreferencesStore
import com.openrad.mobile.engine.EngineConfig
import com.openrad.mobile.engine.EngineState
import com.openrad.mobile.engine.Ip
import com.openrad.mobile.engine.SocketProtector
import com.openrad.mobile.engine.Tunnel
import com.openrad.mobile.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground VPN service: owns the engine lifecycle and the L3 TUN.
 *
 * Android has no TAP for regular apps, so the TUN carries IPv4 only
 * (26.0.0.0/8, MTU 1500) and [com.openrad.mobile.engine.L3Bridge] converts
 * packets to/from the Ethernet frames Radmin peers expect. Engine sockets are
 * excluded from the tunnel with [protect].
 */
class OpenRadVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serviceGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        serviceGeneration = EngineHost.registerService()
    }
    private var watcher: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var tunVip: Int? = null
    private var tunAttachment: EngineHost.Attachment? = null
    private var reader: Thread? = null
    // One flag per reader generation: re-establishing must never clear the
    // stop request of a reader that is still winding down.
    private var readerStop: AtomicBoolean? = null
    private val tunLock = Any()
    // Serializes Disconnect and the rename restart; while a restart is in
    // progress the watcher must not treat its intermediate STOPPED as the end.
    private val lifecycle = Mutex()
    private val restarting = AtomicBoolean(false)
    private val disconnecting = AtomicBoolean(false)
    private val protector = object : SocketProtector {
        override fun protect(socket: Socket): Boolean = this@OpenRadVpnService.protect(socket)
        override fun protect(socket: DatagramSocket): Boolean = this@OpenRadVpnService.protect(socket)
        override fun onProtectFailed(kind: String) {
            if (protectWarned.compareAndSet(false, true)) {
                Log.w(TAG, "VpnService.protect() refused a $kind socket; continuing unprotected (the tunnel only routes 26.0.0.0/8)")
            }
        }
    }
    private val protectWarned = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_RETRY_INTERFACE -> {
                scope.launch {
                    lifecycle.withLock {
                        val attachment = EngineHost.attachment(serviceGeneration) ?: return@withLock
                        val vip = attachment.engine.snapshot().vip ?: return@withLock
                        if (!disconnecting.get()) establish(vip, attachment, force = true)
                    }
                }
                return START_STICKY
            }
            ACTION_RECONNECT -> {
                reconnect()
                return START_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                EngineHost.updateConfig { debugEndpoint(this, engineConfig(PreferencesStore(this).settings())) }
                return START_STICKY
            }
            else -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        disconnecting.set(false)
        startForegroundCompat(buildNotification(getString(R.string.status_connecting)))
        scope.launch {
            lifecycle.withLock {
                if (!disconnecting.get()) startSession()
            }
        }
    }

    private fun startSession() {
        if (!EngineHost.ownsService(serviceGeneration)) return
        try {
            EngineHost.start(this, { debugEndpoint(this, engineConfig(PreferencesStore(this).settings())) }, protector, serviceGeneration)
        } catch (e: Exception) {
            if (EngineHost.ownsService(serviceGeneration)) {
                EngineHost.emit(e.message ?: getString(R.string.error_not_connected), true)
                closeTun()
                stopSelfCompat()
            }
            return
        }
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                EngineHost.state
                    .map { state -> TunState(state.phase, state.vip, state.hostGeneration, state.sessionGeneration,
                        state.peers.count { it.link == com.openrad.mobile.engine.LinkState.CONNECTED }) }
                    .distinctUntilChanged()
                    .collect { state ->
                        if (!EngineHost.ownsService(serviceGeneration)) return@collect
                        val current = EngineHost.state.value
                        if (current.hostGeneration != state.hostGeneration || current.sessionGeneration != state.sessionGeneration ||
                            current.phase != state.phase) return@collect
                        when (state.phase) {
                            EngineState.Phase.ONLINE -> {
                                val attachment = EngineHost.attachment(serviceGeneration)
                                if (attachment != null && !disconnecting.get() && !restarting.get() &&
                                    attachment.session == state.sessionGeneration && state.vip != null) {
                                    establish(state.vip, attachment)
                                    notify(getString(R.string.notification_online, Ip.format(state.vip), state.connected))
                                }
                            }
                            else -> {
                                closeTun()
                                if (state.phase in setOf(EngineState.Phase.STOPPED, EngineState.Phase.ERROR) &&
                                    !restarting.get() && !disconnecting.get() && EngineHost.engine?.isRunning != true) {
                                    stopSelfCompat()
                                } else {
                                    notify(getString(R.string.status_connecting))
                                }
                            }
                        }
                    }
            }
        }
    }

    private fun disconnect() {
        if (!disconnecting.compareAndSet(false, true)) return
        scope.launch {
            lifecycle.withLock {
                closeTun()
                if (!EngineHost.stopForService(serviceGeneration)) {
                    EngineHost.emit("VPN engine is still shutting down.", true)
                }
                if (disconnecting.get() && EngineHost.ownsService(serviceGeneration)) stopSelfCompat()
            }
        }
    }

    private fun reconnect() {
        if (EngineHost.engine?.isRunning != true || !restarting.compareAndSet(false, true)) return
        startForegroundCompat(buildNotification(getString(R.string.status_connecting)))
        scope.launch {
            try {
                lifecycle.withLock {
                    if (disconnecting.get()) return@withLock
                    closeTun()
                    if (!EngineHost.stopForService(serviceGeneration)) {
                        EngineHost.emit("VPN engine is still shutting down.", true)
                        stopSelfCompat()
                        return@withLock
                    }
                    if (!disconnecting.get()) startSession()
                }
            } finally {
                restarting.set(false)
                if (!disconnecting.get() && EngineHost.ownsService(serviceGeneration)) {
                    EngineHost.attachment(serviceGeneration)?.let { attachment ->
                        attachment.engine.snapshot().vip?.let { vip -> establish(vip, attachment) }
                    }
                }
            }
        }
    }

    private data class TunState(val phase: EngineState.Phase, val vip: Int?, val hostGeneration: Long,
                                val sessionGeneration: Long, val connected: Int)

    private fun establish(vip: Int, attachment: EngineHost.Attachment, force: Boolean = false) = synchronized(tunLock) {
        if (EngineHost.attachment(serviceGeneration) != attachment || disconnecting.get()) return@synchronized
        if (!force && tunVip == vip && tunAttachment == attachment && tun != null) return@synchronized
        closeTunLocked()
        if (prepare(this) != null) {
            EngineHost.detachInterface(serviceGeneration, getString(R.string.interface_permission_missing))
            return@synchronized
        }
        val pfd = try {
            val lan = PreferencesStore(this).settings().outgoingBroadcasts
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(Ip.format(vip), 8)
                .addRoute("26.0.0.0", 8)
                .also { b ->
                    // Same routes as the desktop `lan` interface (linux.rs / tap_windows_contract.rs):
                    // app LAN discovery (Minecraft Java 224.0.2.60:4445, Bedrock 255.255.255.255:19132)
                    // enters the TUN and the L3 bridge fans it out to every connected peer.
                    if (lan) b.addRoute("224.0.0.0", 4).addRoute("255.255.255.255", 32)
                }
                .setMtu(Tunnel.MTU)
                .setBlocking(false)
                .also { b -> PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE).let(b::setConfigureIntent) }
                .establish()
        } catch (e: Exception) {
            Log.w(TAG, "TUN setup failed: ${e.javaClass.simpleName}")
            null
        }
        if (pfd == null) {
            EngineHost.detachInterface(serviceGeneration, getString(R.string.interface_failed))
            return@synchronized
        }
        val readerFd = try {
            ParcelFileDescriptor.dup(pfd.fileDescriptor)
        } catch (e: java.io.IOException) {
            runCatching { pfd.close() }
            EngineHost.detachInterface(serviceGeneration, getString(R.string.interface_failed))
            return@synchronized
        }
        val stop = AtomicBoolean(false)
        tun = pfd
        tunVip = vip
        tunAttachment = attachment
        readerStop = stop
        val accepted = EngineHost.attachInterface(serviceGeneration, attachment) { packet ->
            synchronized(tunLock) write@{
                if (stop.get() || tun !== pfd || EngineHost.attachment(serviceGeneration) != attachment) return@write
                try {
                    Os.write(pfd.fileDescriptor, packet, 0, packet.size)
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) {
                        closeTunLocked(getString(R.string.interface_failed))
                    }
                } catch (_: java.io.IOException) {
                    closeTunLocked(getString(R.string.interface_failed))
                }
            }
        }
        if (!accepted) {
            runCatching { readerFd.close() }
            closeTunLocked()
            return@synchronized
        }
        reader = Thread({ readLoop(pfd, readerFd, attachment, stop) }, "openrad-tun").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(pfd: ParcelFileDescriptor, readerFd: ParcelFileDescriptor,
                         attachment: EngineHost.Attachment, stop: AtomicBoolean) {
        val fd = readerFd.fileDescriptor
        val buffer = ByteArray(Tunnel.MTU + 64)
        val poll = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        val pollfds = arrayOf(poll)
        while (!stop.get()) {
            try {
                poll.revents = 0
                if (Os.poll(pollfds, 500) <= 0) continue
                when (tunPollAction(
                    poll.revents.toInt(),
                    OsConstants.POLLIN,
                    OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL,
                )) {
                    TunPollAction.STOP -> break
                    TunPollAction.WAIT -> continue
                    TunPollAction.READ -> Unit
                }
                val n = Os.read(fd, buffer, 0, buffer.size)
                if (n <= 0) break
                if (stop.get() || EngineHost.attachment(serviceGeneration) != attachment) break
                attachment.engine.sendIpInSession(attachment.session, buffer, n)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                break
            } catch (_: java.io.IOException) {
                // A persistently invalid/closed fd otherwise loops on POLLIN
                // and burns CPU. The interface can be retried from the UI.
                break
            } catch (_: Exception) {
                break
            }
        }
        runCatching { readerFd.close() }
        // A previous generation must never clear a TUN established by a
        // reconnect, rename or manual retry after this reader started.
        synchronized(tunLock) {
            if (!stop.get() && readerStop === stop && tun === pfd) {
                Log.w(TAG, "TUN reader failed; interface can be retried")
                closeTunLocked(getString(R.string.interface_failed))
            }
        }
    }

    private fun closeTun() {
        // Never hold tunLock across the join: establish() needs the same lock,
        // and onDestroy() must not block the main thread.
        val stale: Thread? = synchronized(tunLock) { closeTunLocked() }
        stale?.join(1500)
    }

    private fun closeTunLocked(error: String? = null): Thread? {
        readerStop?.set(true)
        readerStop = null
        EngineHost.detachInterface(serviceGeneration, error)
        try {
            tun?.close()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "TUN close failed: ${e.javaClass.simpleName}")
        }
        val stale = reader
        reader = null
        tun = null
        tunVip = null
        tunAttachment = null
        return stale
    }

    override fun onRevoke() {
        // The user granted the VPN to another app or revoked it: stop like Disconnect.
        disconnect()
    }

    override fun onDestroy() {
        val generation = serviceGeneration
        val expected = EngineHost.retireService(generation)
        val stale: Thread? = synchronized(tunLock) { closeTunLocked() }
        scope.cancel()
        if (stale != null || expected != null) {
            Thread({
                expected?.let { EngineHost.stopAfterServiceDestroyed(generation, it) }
                stale?.join(1500)
            }, "openrad-service-close").apply {
                isDaemon = true
                start()
            }
        }
        super.onDestroy()
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun notify(text: String) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OpenRadVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, getString(R.string.vpn_channel_id))
            .setContentTitle(getString(R.string.vpn_active))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, getString(R.string.disconnect), stop)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "OpenRad"
        private const val NOTIF_ID = 41
        const val ACTION_CONNECT = "com.openrad.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.openrad.mobile.DISCONNECT"
        const val ACTION_RETRY_INTERFACE = "com.openrad.mobile.RETRY_INTERFACE"
        const val ACTION_UPDATE_CONFIG = "com.openrad.mobile.UPDATE_CONFIG"
        const val ACTION_RECONNECT = "com.openrad.mobile.RECONNECT"

        fun engineConfig(s: AppSettings) = EngineConfig(
            nodeName = s.nodeName,
            forceRelay = s.forceRelay,
            autoReconnect = s.autoReconnect,
            reconnectAttempts = s.reconnectAttempts,
            reconnectBaseDelaySeconds = s.reconnectBaseDelaySeconds,
            outgoingBroadcasts = s.outgoingBroadcasts,
            blocked = s.blocked,
        )

        /**
         * Debuggable builds only: `files/debug-endpoint.json` ({"host","port","modulus"}) points the
         * engine at a local synthetic coordinator (like the Rust CLI `--host`). Release builds ignore it.
         */
        fun debugEndpoint(context: Context, config: EngineConfig): EngineConfig {
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return config
            val file = java.io.File(context.filesDir, "debug-endpoint.json")
            if (!file.isFile || file.length() > 4096) return config
            return runCatching {
                val o = org.json.JSONObject(file.readText())
                config.copy(
                    host = o.getString("host").also { Ip.parse(it) },
                    port = o.getInt("port"),
                    modulus = com.openrad.mobile.engine.hexToBytes(o.getString("modulus")),
                )
            }.getOrElse { config }
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, OpenRadVpnService::class.java).setAction(action)
            if (action == ACTION_CONNECT) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                runCatching { context.startService(intent) }
            }
        }
    }
}
