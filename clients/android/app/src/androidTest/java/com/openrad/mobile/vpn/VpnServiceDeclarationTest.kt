package com.openrad.mobile.vpn

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.openrad.mobile.engine.EngineConfig
import com.openrad.mobile.engine.EngineState
import com.openrad.mobile.engine.Identity
import com.openrad.mobile.engine.Ip
import com.openrad.mobile.engine.MemoryIdentityStore
import com.openrad.mobile.engine.SocketProtector
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android device/emulator contract, not a connection to a real Radmin network.
 * No VPN is established and no privileged operation is performed here.
 */
@RunWith(AndroidJUnit4::class)
class VpnServiceDeclarationTest {
    @Test
    fun vpnServiceIsProtectedAndConsentApiWorksOnAndroidRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, OpenRadVpnService::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, 0)
        assertEquals("android.permission.BIND_VPN_SERVICE", info.permission)
        assertFalse("VPN service must not be exported", info.exported)
        assertNotNull("VPN service metadata missing", info)
        // The call exercises Android's real VpnService API, without granting
        // consent or modifying any network state.
        VpnService.prepare(context)
    }

    @Test
    fun debugBuildMatchesReleaseCandidateMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("com.openrad.mobile.debug", context.packageName)
        assertEquals("2.3.0", pkg.versionName)
    }

    @Test
    fun destroyingTheBoundVpnServiceStopsItsRunningEngine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, OpenRadVpnService::class.java)
        val manager = context.getSystemService(ActivityManager::class.java)
        fun servicePresent(): Boolean {
            @Suppress("DEPRECATION")
            return manager.getRunningServices(100).any { it.service == component }
        }
        val engineField = hostField("engine")
        val storeField = hostField("identityStore")
        val policyField = hostField("networkPolicy")
        assumeTrue("lifecycle fixture requires an unused engine host", engineField.get(null) == null
            && policyField.get(null) == null && EngineHost.packetSink == null && !servicePresent())
        val previousStore = storeField.get(null)
        @Suppress("UNCHECKED_CAST")
        val state = hostField("_state").get(null) as MutableStateFlow<EngineState>
        val previousState = state.value
        val previousInterface = EngineHost.interfaceStatus.value
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        var bound = false
        try {
            // This is a real Android binding and teardown. Binding alone does
            // not call onStartCommand, establish a TUN or request VPN consent.
            bound = context.bindService(Intent(VpnService.SERVICE_INTERFACE).setComponent(component),
                connection, Context.BIND_AUTO_CREATE)
            assertTrue("Android did not bind the VPN service", bound)
            assertTrue("Android did not deliver onServiceConnected", connected.await(10, TimeUnit.SECONDS))
            assertTrue("bound service missing from Android's own-service list", servicePresent())
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { coordinator ->
                coordinator.soTimeout = 10_000
                val identity = Identity(12345, Ip.parse("26.0.0.5"), "lifecycle-fixture",
                    ByteArray(16) { 1 }, "synthetic-password".toByteArray(), "127.0.0.1")
                // Only persistence and socket protection are fixture adapters;
                // EngineHost, Engine and the Android service remain the real ones.
                storeField.set(null, MemoryIdentityStore(identity))
                val protector = object : SocketProtector {
                    override fun protect(socket: Socket) = true
                    override fun protect(socket: DatagramSocket) = true
                }
                val engine = EngineHost.start(context, EngineConfig(host = "127.0.0.1",
                    port = coordinator.localPort, nodeName = identity.nodeName,
                    autoReconnect = true, reconnectAttempts = 10, reconnectBaseDelaySeconds = 30,
                    forceRelay = true), protector)
                // Hold the local handshake open; no registration or server
                // response is sent and no external address is contacted.
                coordinator.accept().use {
                    assertTrue("fixture engine did not start", engine.isRunning)
                    context.unbindService(connection)
                    bound = false
                    awaitCondition("Android did not remove the unbound service") { !servicePresent() }
                    instrumentation.waitForIdleSync()
                    awaitCondition("destroyed VPN service left its engine running") { !engine.isRunning }
                }
            }
        } finally {
            if (bound) context.unbindService(connection)
            instrumentation.waitForIdleSync()
            // JUnit runs off the main thread; this bounded stop is test cleanup.
            EngineHost.stop()
            storeField.set(null, previousStore)
            engineField.set(null, null)
            state.value = previousState
            EngineHost.setInterface(previousInterface.ready, previousInterface.error)
        }
    }

    private fun hostField(name: String) = EngineHost::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue(message, predicate())
    }
}
