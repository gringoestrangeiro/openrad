package com.openrad.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrad.mobile.R
import com.openrad.mobile.ui.OpenRadViewModel
import com.openrad.mobile.ui.theme.Amber
import com.openrad.mobile.ui.theme.Mint
import com.openrad.mobile.ui.theme.Muted
import com.openrad.mobile.ui.theme.Raised

@Composable
fun DiscoverScreen(vm: OpenRadViewModel) {
    val results by vm.publicResults.collectAsStateWithLifecycle()
    val busy by vm.publicBusy.collectAsStateWithLifecycle()
    val hasMore by vm.publicHasMore.collectAsStateWithLifecycle()
    val query by vm.discoverQuery.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.discover_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.discover_subtitle), style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.discoverQuery.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!status.connected) {
            item { Text(stringResource(R.string.connect_to_search), color = Amber, style = MaterialTheme.typography.bodySmall) }
        } else if (results.isEmpty() && !busy) {
            item {
                Text(stringResource(R.string.no_networks_found))
                Text(stringResource(R.string.try_shorter), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        items(results, key = { it.name }) { net ->
            Card(colors = CardDefaults.cardColors(containerColor = Raised)) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(net.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.members_reported, net.reportedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                    IconButton(onClick = { vm.toggleFavorite(net.name) }) {
                        Icon(
                            if (net.name in favorites) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = stringResource(R.string.favorites_title),
                        )
                    }
                    if (net.joined) {
                        Text(stringResource(R.string.joined), style = MaterialTheme.typography.labelMedium, color = Mint)
                    } else {
                        Button(onClick = { vm.joinPublic(net.name) }, enabled = status.connected) {
                            Text(stringResource(R.string.join_network))
                        }
                    }
                }
            }
        }
        if (hasMore) {
            item {
                OutlinedButton(onClick = { vm.loadMorePublic() }, enabled = !busy) { Text(stringResource(R.string.load_more)) }
            }
        }
        item { Text("", Modifier.padding(bottom = 8.dp)) }
    }
}
