package com.openrad.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openrad.mobile.R
import com.openrad.mobile.model.Peer
import com.openrad.mobile.model.PeerState
import com.openrad.mobile.model.Transport
import com.openrad.mobile.ui.theme.Amber
import com.openrad.mobile.ui.theme.Blue
import com.openrad.mobile.ui.theme.Mint
import com.openrad.mobile.ui.theme.Muted
import com.openrad.mobile.ui.theme.Raised
import com.openrad.mobile.ui.theme.Red

@Composable
fun PeerRow(
    peer: Peer,
    showEndpoint: Boolean,
    showDetails: Boolean,
    showInternalIds: Boolean,
    onTestRtt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Raised),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleSmall)
                    Text(peer.vip, style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (showInternalIds) {
                        Text(
                            stringResource(R.string.rid_label, java.lang.Long.toUnsignedString(peer.rid)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
                PeerStatusChip(peer.state)
            }
            val transportLabel = when (peer.transport) {
                Transport.DIRECT_TCP -> stringResource(R.string.transport_direct_tcp)
                Transport.DIRECT_UDP -> stringResource(R.string.transport_direct_udp)
                Transport.RELAY -> stringResource(R.string.transport_relay)
                null -> null
            }
            if (transportLabel != null || (showEndpoint && peer.endpoint != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (transportLabel != null) {
                        Text(transportLabel, style = MaterialTheme.typography.labelSmall, color = Blue)
                    }
                    if (showEndpoint && peer.endpoint != null) {
                        Text(peer.endpoint, style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
            val failing = peer.state == PeerState.FAILED || peer.state == PeerState.REFUSED
            if (peer.detail.isNotBlank() && (showDetails || failing)) {
                Text(peer.detail, style = MaterialTheme.typography.bodySmall, color = if (failing) Red else Muted)
            }
            if (peer.state == PeerState.CONNECTED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onTestRtt, enabled = !peer.rttRunning) { Text(stringResource(R.string.test_rtt)) }
                    when {
                        peer.rttRunning -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        peer.rttMs != null -> Text(stringResource(R.string.rtt_ms, peer.rttMs), style = MaterialTheme.typography.bodySmall, color = Mint)
                        peer.rttFailed -> Text(stringResource(R.string.rtt_no_response), style = MaterialTheme.typography.bodySmall, color = Amber)
                    }
                }
            }
        }
    }
}

@Composable
fun PeerStatusChip(state: PeerState) {
    val (label, icon, color) = when (state) {
        PeerState.CONNECTED -> Triple(stringResource(R.string.peer_connected), Icons.Outlined.TaskAlt, Mint)
        PeerState.CONNECTING -> Triple(stringResource(R.string.peer_connecting), Icons.Outlined.Loop, Amber)
        PeerState.ONLINE_QUEUED -> Triple(stringResource(R.string.peer_queued), Icons.Outlined.CloudQueue, Blue)
        PeerState.FAILED -> Triple(stringResource(R.string.peer_failed), Icons.Outlined.ErrorOutline, Red)
        PeerState.REFUSED -> Triple(stringResource(R.string.peer_refused), Icons.Outlined.ErrorOutline, Red)
        PeerState.UNAVAILABLE -> Triple(stringResource(R.string.peer_unavailable), Icons.Outlined.HourglassEmpty, Amber)
        PeerState.OFFLINE -> Triple(stringResource(R.string.peer_offline), Icons.Outlined.CloudOff, Muted)
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color.takeIf { it != Color.Unspecified } ?: Muted),
    )
}
