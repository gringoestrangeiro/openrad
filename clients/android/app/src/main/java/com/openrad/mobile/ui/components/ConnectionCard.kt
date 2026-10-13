package com.openrad.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openrad.mobile.R
import com.openrad.mobile.model.ConnectionPhase
import com.openrad.mobile.model.ConnectionStatus
import com.openrad.mobile.model.Snapshot
import com.openrad.mobile.ui.theme.Amber
import com.openrad.mobile.ui.theme.Mint
import com.openrad.mobile.ui.theme.Muted
import com.openrad.mobile.ui.theme.Raised
import com.openrad.mobile.ui.theme.Red
import com.openrad.mobile.util.formatBytes

@Composable
fun ConnectionCard(
    status: ConnectionStatus,
    snapshot: Snapshot,
    showTraffic: Boolean,
    decimalUnits: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetryInterface: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Raised),
    ) {
        Column(Modifier.padding(16.dp)) {
            val (phaseLabel, color) = when (status.phase) {
                ConnectionPhase.CONNECTED -> stringResource(R.string.status_connected) to Mint
                ConnectionPhase.PROVISIONING -> stringResource(R.string.status_provisioning) to Amber
                ConnectionPhase.CONNECTING -> stringResource(R.string.status_connecting) to Amber
                ConnectionPhase.DISCONNECTING -> stringResource(R.string.status_disconnecting) to Amber
                ConnectionPhase.ERROR -> stringResource(R.string.status_error) to Red
                ConnectionPhase.RESETTING -> stringResource(R.string.status_resetting) to Amber
                ConnectionPhase.IDLE -> stringResource(R.string.status_disconnected) to Muted
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.phase == ConnectionPhase.CONNECTING || status.phase == ConnectionPhase.PROVISIONING) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
                    Spacer(Modifier.size(8.dp))
                }
                Text(phaseLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            }
            if (status.message.isNotBlank()) {
                Text(status.message, style = MaterialTheme.typography.bodySmall, color = if (status.phase == ConnectionPhase.ERROR) Red else Muted)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.vpn_address_label, snapshot.vip ?: stringResource(R.string.awaiting_assignment)),
                style = MaterialTheme.typography.bodyMedium,
            )
            snapshot.nodeName?.let {
                Text(stringResource(R.string.node_label, it), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (showTraffic && status.connected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.traffic_summary,
                        formatBytes(snapshot.traffic.receivedBytes, decimalUnits),
                        formatBytes(snapshot.traffic.sentBytes, decimalUnits),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (status.phase == ConnectionPhase.CONNECTED) {
                Spacer(Modifier.height(4.dp))
                if (snapshot.interfaceReady) {
                    Text(stringResource(R.string.interface_ready), style = MaterialTheme.typography.bodySmall, color = Mint)
                } else {
                    Text(
                        snapshot.interfaceError ?: stringResource(R.string.interface_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                    OutlinedButton(onClick = onRetryInterface) { Text(stringResource(R.string.retry_interface)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.connected) {
                    Button(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                } else {
                    Button(onClick = onConnect) { Text(stringResource(R.string.connect)) }
                }
            }
            if (!status.connected && status.phase == ConnectionPhase.IDLE) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.bring_devices_same_network), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
