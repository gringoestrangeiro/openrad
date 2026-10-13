package com.openrad.mobile

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openrad.mobile.model.ConnectionPhase
import com.openrad.mobile.model.StartPage
import com.openrad.mobile.navigation.Destination
import com.openrad.mobile.ui.OpenRadViewModel
import com.openrad.mobile.ui.screens.AutoJoinScreen
import com.openrad.mobile.ui.screens.DiscoverScreen
import com.openrad.mobile.ui.screens.NetworksScreen
import com.openrad.mobile.ui.screens.SettingsScreen
import com.openrad.mobile.ui.theme.OpenRadTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var vm: OpenRadViewModel

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Even without consent the control session runs (catalog/joins); the service reports the missing interface.
        vm.connect()
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else vm.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            vm = viewModel(factory = OpenRadViewModel.factory(application))
            val settings by vm.settings.collectAsStateWithLifecycle()
            val status by vm.status.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                // Auto-connect only when the VPN consent already exists; never prompt unexpectedly.
                if (settings.autoConnect && status.phase == ConnectionPhase.IDLE && VpnService.prepare(this@MainActivity) == null) {
                    vm.connect()
                }
            }
            Localized(settings.language, settings.scale) {
                OpenRadTheme {
                    val nav = rememberNavController()
                    val backStack by nav.currentBackStackEntryAsState()
                    val route = backStack?.destination?.route
                    val start = remember { if (settings.startPage == StartPage.DISCOVER) Destination.Discover else Destination.Networks }
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavItem(Destination.Networks, R.string.nav_networks, route, nav)
                                NavItem(Destination.Discover, R.string.nav_discover, route, nav)
                                NavItem(Destination.AutoJoin, R.string.nav_autojoin, route, nav)
                                NavItem(Destination.Settings, R.string.nav_settings, route, nav)
                            }
                        },
                    ) { inner ->
                        NavHost(
                            navController = nav,
                            startDestination = start.route,
                            modifier = Modifier.padding(inner),
                        ) {
                            composable(Destination.Networks.route) { NetworksScreen(vm, onConnect = ::requestConnect) }
                            composable(Destination.Discover.route) { DiscoverScreen(vm) }
                            composable(Destination.AutoJoin.route) {
                                AutoJoinScreen(vm, onBrowsePublic = { navigate(nav, Destination.Discover) })
                            }
                            composable(Destination.Settings.route) { SettingsScreen(vm) }
                        }
                    }
                }
            }
        }
    }

    private fun navigate(nav: NavController, dest: Destination) {
        nav.navigate(dest.route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    @Composable
    private fun RowScope.NavItem(dest: Destination, labelRes: Int, route: String?, nav: NavController) {
        NavigationBarItem(
            selected = route == dest.route,
            onClick = { navigate(nav, dest) },
            icon = { Icon(dest.icon, contentDescription = null) },
            label = { Text(stringResource(labelRes)) },
        )
    }
}

/** Applies the in-app language (en/pt/ru/vi or system) and interface scale 0.8–1.5 without recreating the activity. */
@Composable
private fun Localized(language: String, scale: Float, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val context = remember(base, configuration, language) {
        if (language == "system") {
            base
        } else {
            val localizedConfig = Configuration(configuration).apply { setLocale(Locale(language)) }
            val resources = base.createConfigurationContext(localizedConfig).resources
            object : ContextWrapper(base) {
                override fun getResources(): Resources = resources
            } as Context
        }
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides context.resources.configuration,
        LocalDensity provides Density(density.density * scale, density.fontScale),
        content = content,
    )
}
