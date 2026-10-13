package com.openrad.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrad.mobile.R
import com.openrad.mobile.engine.Protocol
import com.openrad.mobile.model.PeerSort
import com.openrad.mobile.model.StartPage
import com.openrad.mobile.ui.OpenRadViewModel
import com.openrad.mobile.ui.theme.Amber
import com.openrad.mobile.ui.theme.Muted
import com.openrad.mobile.ui.theme.Raised
import com.openrad.mobile.ui.theme.Red
import java.util.Locale

@Composable
fun SettingsScreen(vm: OpenRadViewModel) {
    val current by vm.settings.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var draft by remember(current) { mutableStateOf(current) }
    var langMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val nameValid = runCatching { Protocol.validateNodeName(draft.nodeName) }.isSuccess

    @Composable
    fun langLabel(code: String): String = when (code) {
        "en" -> stringResource(R.string.lang_en)
        "pt" -> stringResource(R.string.lang_pt)
        "ru" -> stringResource(R.string.lang_ru)
        "vi" -> stringResource(R.string.lang_vi)
        else -> stringResource(R.string.lang_system)
    }

    @Composable
    fun sortLabel(sort: PeerSort) = when (sort) {
        PeerSort.NAME -> stringResource(R.string.sort_name)
        PeerSort.STATUS -> stringResource(R.string.sort_status)
        PeerSort.ADDRESS -> stringResource(R.string.sort_address)
    }

    @Composable
    fun pageLabel(page: StartPage) = when (page) {
        StartPage.NETWORKS -> stringResource(R.string.nav_networks)
        StartPage.DISCOVER -> stringResource(R.string.nav_discover)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Section(stringResource(R.string.settings_language), Modifier.padding(top = 16.dp)) }
        item {
            OutlinedButton(onClick = { langMenu = true }) { Text(langLabel(draft.language)) }
            DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                com.openrad.mobile.model.AppSettings.LANGUAGES.forEach { code ->
                    DropdownMenuItem(text = { Text(langLabel(code)) }, onClick = {
                        draft = draft.copy(language = code)
                        langMenu = false
                    })
                }
            }
        }
        item { Section(stringResource(R.string.settings_connection)) }
        item {
            Toggle(stringResource(R.string.settings_autoconnect), draft.autoConnect) { draft = draft.copy(autoConnect = it) }
            Toggle(stringResource(R.string.settings_autoreconnect), draft.autoReconnect) { draft = draft.copy(autoReconnect = it) }
            Toggle(stringResource(R.string.settings_force_relay), draft.forceRelay) { draft = draft.copy(forceRelay = it) }
            Toggle(stringResource(R.string.settings_outgoing_broadcasts), draft.outgoingBroadcasts) { draft = draft.copy(outgoingBroadcasts = it) }
            Text(stringResource(R.string.settings_outgoing_broadcasts_hint), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        item {
            Text(stringResource(R.string.settings_max_attempts) + ": ${draft.reconnectAttempts}")
            Slider(
                value = draft.reconnectAttempts.toFloat(),
                onValueChange = { draft = draft.copy(reconnectAttempts = it.toInt().coerceIn(1, 10)) },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text(stringResource(R.string.settings_retry_delay) + ": ${draft.reconnectBaseDelaySeconds}")
            Slider(
                value = draft.reconnectBaseDelaySeconds.toFloat(),
                onValueChange = { draft = draft.copy(reconnectBaseDelaySeconds = it.toInt().coerceIn(1, 30)) },
                valueRange = 1f..30f,
                steps = 28,
            )
        }
        item {
            OutlinedTextField(
                value = draft.nodeName,
                onValueChange = { draft = draft.copy(nodeName = it) },
                label = { Text(stringResource(R.string.settings_device_name)) },
                singleLine = true,
                isError = !nameValid,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.settings_rename_note), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        item { Section(stringResource(R.string.settings_workspace)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_start_page), Modifier.weight(1f))
                OutlinedButton(onClick = { pageMenu = true }) { Text(pageLabel(draft.startPage)) }
                DropdownMenu(expanded = pageMenu, onDismissRequest = { pageMenu = false }) {
                    StartPage.entries.forEach { page ->
                        DropdownMenuItem(text = { Text(pageLabel(page)) }, onClick = {
                            draft = draft.copy(startPage = page)
                            pageMenu = false
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_sort), Modifier.weight(1f))
                OutlinedButton(onClick = { sortMenu = true }) { Text(sortLabel(draft.peerSort)) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    PeerSort.entries.forEach { sort ->
                        DropdownMenuItem(text = { Text(sortLabel(sort)) }, onClick = {
                            draft = draft.copy(peerSort = sort)
                            sortMenu = false
                        })
                    }
                }
            }
            Toggle(stringResource(R.string.settings_show_traffic), draft.showTraffic) { draft = draft.copy(showTraffic = it) }
            Toggle(stringResource(R.string.settings_decimal), draft.decimalUnits) { draft = draft.copy(decimalUnits = it) }
            Toggle(stringResource(R.string.settings_offline), draft.showOfflinePeers) { draft = draft.copy(showOfflinePeers = it) }
            Toggle(stringResource(R.string.settings_recent), draft.showRecentActivity) { draft = draft.copy(showRecentActivity = it) }
        }
        item {
            Text(stringResource(R.string.settings_recent_count) + ": ${draft.recentActivityCount}")
            Slider(
                value = draft.recentActivityCount.toFloat(),
                onValueChange = { draft = draft.copy(recentActivityCount = it.toInt().coerceIn(5, 40)) },
                valueRange = 5f..40f,
                steps = 34,
            )
            Text(stringResource(R.string.settings_scale) + ": " + String.format(Locale.US, "%.2f×", draft.scale))
            Slider(
                value = draft.scale,
                onValueChange = { draft = draft.copy(scale = (Math.round(it * 20) / 20f).coerceIn(0.8f, 1.5f)) },
                valueRange = 0.8f..1.5f,
                steps = 13,
            )
        }
        item { Section(stringResource(R.string.settings_developer)) }
        item {
            Toggle(stringResource(R.string.settings_peer_details), draft.showPeerDetails) { draft = draft.copy(showPeerDetails = it) }
            Toggle(stringResource(R.string.settings_peer_ip), draft.showPeerIp) { draft = draft.copy(showPeerIp = it) }
            Toggle(stringResource(R.string.settings_internal_ids), draft.showInternalIds) { draft = draft.copy(showInternalIds = it) }
            Toggle(stringResource(R.string.settings_diagnostics), draft.showDiagnostics) { draft = draft.copy(showDiagnostics = it) }
        }
        item {
            Button(enabled = nameValid && draft != current, onClick = { vm.saveSettings(draft) }) {
                Text(stringResource(R.string.settings_save))
            }
        }
        item { Section(stringResource(R.string.settings_identity)) }
        item {
            Text(
                snapshot.rid?.let { stringResource(R.string.identity_summary, java.lang.Long.toUnsignedString(it), snapshot.vip ?: "—") }
                    ?: stringResource(R.string.identity_none),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            OutlinedButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.settings_reset), color = Red) }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Raised), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.limitations_title), style = MaterialTheme.typography.titleSmall, color = Amber)
                    Text(stringResource(R.string.limitations_body), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("", Modifier.padding(bottom = 8.dp)) }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_confirm_title),
            body = stringResource(R.string.settings_reset_confirm_desc),
            confirm = stringResource(R.string.settings_reset_confirm),
            onConfirm = {
                vm.resetIdentity()
                confirmReset = false
            },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
