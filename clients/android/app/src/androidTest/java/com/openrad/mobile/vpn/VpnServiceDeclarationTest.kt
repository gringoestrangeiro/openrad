package com.openrad.mobile.vpn

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

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
        assertEquals("2.2.0", pkg.versionName)
    }
}
