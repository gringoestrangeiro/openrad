package com.openrad.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.MemberRole
import com.openrad.mobile.model.NetworkInfo
import com.openrad.mobile.model.PeerState
import com.openrad.mobile.model.Snapshot
import com.openrad.mobile.ui.OpenRadViewModel
import com.openrad.mobile.ui.components.ConnectionCard
import com.openrad.mobile.ui.components.PeerRow
import com.openrad.mobile.ui.theme.Amber
import com.openrad.mobile.ui.theme.Mint
import com.openrad.mobile.ui.theme.Muted
import com.openrad.mobile.ui.theme.Raised
import com.openrad.mobile.ui.theme.Red
import com.openrad.mobile.util.filterAndSortPeers

@Composable
fun NetworksScreen(vm: OpenRadViewModel, onConnect: () -> Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val selectedNetwork by vm.selectedNetwork.collectAsStateWithLifecycle()
    val peerFilter by vm.peerFilter.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf<NetworkInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<NetworkInfo?>(null) }

    // Mirrors PeerListCache: recompute only when the inputs change.
    val visiblePeers = remember(snapshot.peers, selectedNetwork, peerFilter, settings.showOfflinePeers, settings.peerSort) {
        filterAndSortPeers(snapshot.peers, peerFilter, selectedNetwork, settings.showOfflinePeers, settings.peerSort)
    }
    val failed = snapshot.peers.count { it.state == PeerState.FAILED || it.state == PeerState.REFUSED }
    val offline = snapshot.peers.count { it.state == PeerState.OFFLINE }
    // Favorites first, then alphabetical (as the desktop network list).
    val networks = remember(snapshot.networks, favorites) {
        snapshot.networks.sortedWith(compareBy({ it.name !in favorites }, { it.name.lowercase() }))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ConnectionCard(
                status = status,
                snapshot = snapshot,
                showTraffic = settings.showTraffic,
                decimalUnits = settings.decimalUnits,
                onConnect = onConnect,
                onDisconnect = { vm.disconnect() },
                onRetryInterface = { vm.retryInterface() },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreate = true }, enabled = status.connected) { Text(stringResource(R.string.create_private)) }
                OutlinedButton(onClick = { showJoin = true }, enabled = status.connected) { Text(stringResource(R.string.join_private)) }
            }
        }
        if (networks.isEmpty()) {
            item {
                Column {
                    Text(stringResource(R.string.no_networks_yet), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.browse_public_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            item { Text(stringResource(R.string.your_networks), style = MaterialTheme.typography.titleSmall) }
            items(networks, key = { "net-" + it.networkId }) { net ->
                NetworkCard(
                    net = net,
                    favorite = net.name in favorites,
                    selected = selectedNetwork == net.networkId,
                    showInternalIds = settings.showInternalIds,
                    onSelect = { vm.selectedNetwork.value = if (selectedNetwork == net.networkId) null else net.networkId },
                    onFavorite = { vm.toggleFavorite(net.name) },
                    onLeave = { confirmLeave = net },
                    onDelete = { confirmDelete = net },
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedNetwork == null,
                            onClick = { vm.selectedNetwork.value = null },
                            label = { Text(stringResource(R.string.all_networks)) },
                        )
                    }
                    items(networks, key = { "chip-" + it.networkId }) { net ->
                        FilterChip(
                            selected = selectedNetwork == net.networkId,
                            onClick = { vm.selectedNetwork.value = if (selectedNetwork == net.networkId) null else net.networkId },
                            label = { Text(net.name) },
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = peerFilter,
                onValueChange = { vm.peerFilter.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.filter_peers_hint)) },
                singleLine = true,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.retryPeers() }, enabled = status.connected) { Text(stringResource(R.string.retry_failed)) }
            }
            Text(
                stringResource(R.string.retry_counts, failed, offline, snapshot.retryActive, snapshot.retryQueued),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        items(visiblePeers, key = { "peer-" + it.rid }) { peer ->
            PeerRow(
                peer = peer,
                showEndpoint = settings.showPeerIp,
                showDetails = settings.showPeerDetails,
                showInternalIds = settings.showInternalIds,
                onTestRtt = { vm.testRtt(peer.rid) },
            )
        }
        if (settings.showDiagnostics) {
            item { Diagnostics(snapshot, settings) }
        }
        if (settings.showRecentActivity) {
            item { Text(stringResource(R.string.recent_activity), style = MaterialTheme.typography.titleSmall) }
            items(activity.take(settings.recentActivityCount), key = { "ev-" + it.id }) { ev ->
                Text(
                    ev.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ev.isError) Red else MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        item { Text("", Modifier.padding(bottom = 8.dp)) }
    }

    if (showCreate) {
        PrivateNetworkDialog(
            create = true,
            onDismiss = { showCreate = false },
            onSubmit = { name, pass ->
                vm.createPrivate(name, pass)
                showCreate = false
            },
        )
    }
    if (showJoin) {
        PrivateNetworkDialog(
            create = false,
            onDismiss = { showJoin = false },
            onSubmit = { name, pass ->
                vm.joinPrivate(name, pass)
                showJoin = false
            },
        )
    }
    confirmLeave?.let { net ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_leave_title, net.name),
            body = stringResource(R.string.confirm_leave_desc),
            confirm = stringResource(R.string.leave_network),
            onConfirm = {
                vm.leaveNetwork(net.networkId)
                if (selectedNetwork == net.networkId) vm.selectedNetwork.value = null
                confirmLeave = null
            },
            onDismiss = { confirmLeave = null },
        )
    }
    confirmDelete?.let { net ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title, net.name),
            body = stringResource(R.string.confirm_delete_desc),
            confirm = stringResource(R.string.delete_network),
            onConfirm = {
                vm.deleteNetwork(net.networkId)
                if (selectedNetwork == net.networkId) vm.selectedNetwork.value = null
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun NetworkCard(
    net: NetworkInfo,
    favorite: Boolean,
    selected: Boolean,
    showInternalIds: Boolean,
    onSelect: () -> Unit,
    onFavorite: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Raised),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(net.name, style = MaterialTheme.typography.titleSmall, color = if (selected) Mint else MaterialTheme.colorScheme.onSurface)
                val (role, color) = when (net.ownRole) {
                    MemberRole.ADMIN -> stringResource(R.string.role_owner) to Mint
                    MemberRole.PENDING -> stringResource(R.string.role_pending) to Amber
                    MemberRole.MEMBER -> stringResource(R.string.role_member) to Muted
                    MemberRole.UNKNOWN -> stringResource(R.string.role_member) to Muted
                }
                Text("$role · ${stringResource(R.string.members_count, net.memberCount)}", style = MaterialTheme.typography.bodySmall, color = color)
                if (showInternalIds) {
                    Text(stringResource(R.string.network_id_label, net.networkId), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = stringResource(R.string.favorites_title))
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.leave_network)) }, onClick = {
                        menu = false
                        onLeave()
                    })
                    if (net.ownRole == MemberRole.ADMIN) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_network), color = Red) }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Diagnostics(snapshot: Snapshot, settings: AppSettings) {
    Card(colors = CardDefaults.cardColors(containerColor = Raised), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.diagnostics_frames,
                    snapshot.traffic.receivedFrames, snapshot.traffic.sentFrames, snapshot.traffic.dropped,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.diagnostics_latency, snapshot.latencyMs, snapshot.pendingIncoming), style = MaterialTheme.typography.bodySmall)
            if (snapshot.forceRelay || settings.forceRelay) {
                Text(stringResource(R.string.settings_force_relay), style = MaterialTheme.typography.bodySmall, color = Amber)
            }
        }
    }
}
