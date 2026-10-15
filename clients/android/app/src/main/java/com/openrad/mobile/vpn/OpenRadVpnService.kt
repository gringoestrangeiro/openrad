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
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private var watcher: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var tunVip: Int? = null
    private var reader: Thread? = null
    // One flag per reader generation: re-establishing must never clear the
    // stop request of a reader that is still winding down.
    private var readerStop: AtomicBoolean? = null
    private val tunLock = Any()
    // Serializes Disconnect and the rename restart; while a restart is in
    // progress the watcher must not treat its intermediate STOPPED as the end.
    private val lifecycle = Mutex()
    @Volatile private var restarting = false
    private val protector = object : SocketProtector {
        override fun protect(socket: Socket): Boolean = this@OpenRadVpnService.protect(socket)
        override fun protect(socket: DatagramSocket): Boolean = this@OpenRadVpnService.protect(socket)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_RETRY_INTERFACE -> {
                synchronized(tunLock) { tunVip = null }
                EngineHost.state.value.takeIf { it.phase == EngineState.Phase.ONLINE }?.vip?.let { establish(it) }
                return START_STICKY
            }
            ACTION_RECONNECT -> {
                reconnect()
                return START_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                EngineHost.updateConfig(debugEndpoint(this, engineConfig(PreferencesStore(this).settings())))
                return START_STICKY
            }
            else -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        startForegroundCompat(buildNotification(getString(R.string.status_connecting)))
        val settings = PreferencesStore(this).settings()
        EngineHost.start(this, debugEndpoint(this, engineConfig(settings)), protector)
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                EngineHost.state
                    .map { Triple(it.phase, it.vip, it.peers.count { p -> p.link == com.openrad.mobile.engine.LinkState.CONNECTED }) }
                    .distinctUntilChanged()
                    .collect { (phase, vip, connected) ->
                        when (phase) {
                            EngineState.Phase.ONLINE -> {
                                if (vip != null && vip != synchronized(tunLock) { tunVip }) establish(vip)
                                notify(getString(R.string.notification_online, Ip.format(vip ?: 0), connected))
                            }
                            EngineState.Phase.STOPPED, EngineState.Phase.ERROR -> {
                                if (!restarting && EngineHost.engine?.isRunning != true) {
                                    closeTun()
                                    stopSelfCompat()
                                }
                            }
                            else -> notify(getString(R.string.status_connecting))
                        }
                    }
            }
        }
    }

    private fun disconnect() {
        scope.launch {
            lifecycle.withLock {
                EngineHost.stop()
                closeTun()
                stopSelfCompat()
            }
        }
    }

    /**
     * Rename: same identity, new engine (like the daemon's Rename). Runs inside
     * the service so the watcher cannot stop it between the old engine's
     * STOPPED and the new engine's start.
     */
    private fun reconnect() {
        if (EngineHost.engine?.isRunning != true) return
        restarting = true
        startForegroundCompat(buildNotification(getString(R.string.status_connecting)))
        scope.launch {
            try {
                lifecycle.withLock {
                    EngineHost.stop()
                    // The new engine starts detached; ONLINE re-establishes the TUN for it.
                    closeTun()
                    connect()
                }
            } finally {
                restarting = false
            }
        }
    }

    private fun establish(vip: Int) = synchronized(tunLock) {
        if (prepare(this) != null) {
            EngineHost.setInterface(false, getString(R.string.interface_permission_missing))
            return@synchronized
        }
        closeTunLocked()
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
            EngineHost.setInterface(false, getString(R.string.interface_failed))
            return@synchronized
        }
        tun = pfd
        tunVip = vip
        val output = FileOutputStream(pfd.fileDescriptor)
        val writeLock = Any()
        EngineHost.packetSink = { packet ->
            try {
                synchronized(writeLock) { output.write(packet) }
            } catch (_: Exception) {
                // Interface closing or kernel queue full: drop like a full TAP queue.
            }
        }
        EngineHost.setInterface(true, null)
        val stop = AtomicBoolean(false)
        readerStop = stop
        reader = Thread({ readLoop(pfd, stop) }, "openrad-tun").apply {
            isDaemon = true
            start()
        }
    }

    /** Reads IPv4 packets with poll(2) so closing the interface stops the thread promptly. */
    private fun readLoop(pfd: ParcelFileDescriptor, stop: AtomicBoolean) {
        val fd = pfd.fileDescriptor
        val input = FileInputStream(fd)
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
                val n = input.read(buffer)
                // The TUN is non-blocking: Android's FileInputStream returns 0
                // for EAGAIN after a spurious wakeup. Only -1 (EOF) is terminal.
                if (n < 0) break
                if (n == 0) continue
                EngineHost.engine?.sendIp(buffer, n)
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
        // Release the input wrapper even when shutdown came from an older
        // reader generation. Its descriptor is owned by this TUN generation.
        runCatching { input.close() }
        // A previous generation must never clear a TUN established by a
        // reconnect, rename or manual retry after this reader started.
        synchronized(tunLock) {
            if (!stop.get() && readerStop === stop && tun === pfd) {
                Log.w(TAG, "TUN reader failed; interface can be retried")
                closeTunLocked()
                EngineHost.setInterface(false, getString(R.string.interface_failed))
            }
        }
    }

    private fun closeTun() {
        // Never hold tunLock across the join: establish() needs the same lock,
        // and onDestroy() must not block the main thread.
        val stale: Thread? = synchronized(tunLock) { closeTunLocked() }
        stale?.join(1500)
    }

    private fun closeTunLocked(): Thread? {
        readerStop?.set(true)
        readerStop = null
        EngineHost.packetSink = null
        EngineHost.setInterface(false, null)
        runCatching { tun?.close() }
        val stale = reader
        reader = null
        tun = null
        tunVip = null
        return stale
    }

    override fun onRevoke() {
        // The user granted the VPN to another app or revoked it: stop like Disconnect.
        disconnect()
    }

    override fun onDestroy() {
        // Signal stop and close the fd synchronously (fast); join the reader on
        // a background thread so the main thread never blocks up to 1500 ms.
        val stale: Thread? = synchronized(tunLock) { closeTunLocked() }
        if (stale != null) {
            Thread({ stale.join(1500) }, "openrad-tun-close").apply {
                isDaemon = true
                start()
            }
        }
        scope.cancel()
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
