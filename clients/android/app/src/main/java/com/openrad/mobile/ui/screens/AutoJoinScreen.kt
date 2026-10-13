package com.openrad.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrad.mobile.R
import com.openrad.mobile.model.JoinConfiguration
import com.openrad.mobile.model.JoinNetwork
import com.openrad.mobile.ui.OpenRadViewModel
import com.openrad.mobile.ui.theme.Amber
import com.openrad.mobile.ui.theme.Mint
import com.openrad.mobile.ui.theme.Muted
import com.openrad.mobile.ui.theme.Raised
import com.openrad.mobile.util.validateConfigName
import com.openrad.mobile.util.validateNetworkName
import com.openrad.mobile.util.validateNetworkPassword

/** Batch join: mirrors the desktop "Auto join" page (50 ms spacing, saved lists without passwords). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoJoinScreen(vm: OpenRadViewModel, onBrowsePublic: () -> Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val configs by vm.joinConfigurations.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val progress by vm.batchProgress.collectAsStateWithLifecycle()

    val added = remember { mutableStateListOf<JoinNetwork>() }
    val privateFlags = remember { mutableStateMapOf<String, Boolean>() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var configName by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newPrivate by remember { mutableStateOf(false) }
    var askPasswords by remember { mutableStateOf<List<JoinNetwork>?>(null) }

    val joinedNames = snapshot.networks.map { it.name }.toSet()
    val candidates = remember(added.toList(), favorites, joinedNames, privateFlags.toMap()) {
        val names = LinkedHashSet<String>()
        added.forEach { names.add(it.name) }
        favorites.sorted().forEach { names.add(it) }
        joinedNames.sorted().forEach { names.add(it) }
        names.map { JoinNetwork(it, privateFlags[it] ?: added.firstOrNull { a -> a.name == it }?.private ?: false) }
    }

    fun startJoin() {
        val nets = candidates.filter { it.name in selected }
        if (nets.any { it.private && it.name !in joinedNames }) askPasswords = nets else vm.runBatchJoin(nets, emptyMap())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.autojoin_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.autojoin_spacing), style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.exact_network_name)) },
                    singleLine = true,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = newPrivate, onCheckedChange = { newPrivate = it })
                Text(stringResource(R.string.private_label), Modifier.weight(1f))
                Button(
                    enabled = validateNetworkName(newName) && candidates.size < JoinConfiguration.MAX_NETWORKS,
                    onClick = {
                        val name = newName
                        added.removeAll { it.name == name }
                        added.add(0, JoinNetwork(name, newPrivate))
                        privateFlags[name] = newPrivate
                        selected = selected + name
                        newName = ""
                    },
                ) { Text(stringResource(R.string.add_network)) }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selected = selected + joinedNames }) { Text(stringResource(R.string.select_joined)) }
                OutlinedButton(onClick = onBrowsePublic) { Text(stringResource(R.string.browse_public)) }
                OutlinedButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.clear_selection)) }
            }
        }
        if (candidates.isEmpty()) {
            item { Text(stringResource(R.string.browse_public_hint), style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        items(candidates, key = { it.name }) { net ->
            Card(colors = CardDefaults.cardColors(containerColor = Raised)) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = net.name in selected,
                        onCheckedChange = { selected = if (it) selected + net.name else selected - net.name },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(net.name)
                        if (net.name in joinedNames) Text(stringResource(R.string.joined), style = MaterialTheme.typography.labelSmall, color = Mint)
                    }
                    Text(stringResource(R.string.private_short), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Checkbox(checked = net.private, onCheckedChange = { privateFlags[net.name] = it })
                }
            }
        }
        item {
            if (!status.connected) {
                Text(stringResource(R.string.connect_to_join), style = MaterialTheme.typography.bodySmall, color = Amber)
            }
            Button(
                enabled = selected.isNotEmpty() && status.connected && progress?.running != true,
                onClick = { startJoin() },
            ) { Text(stringResource(R.string.join_selected, selected.size)) }
            progress?.let { p ->
                if (p.running) LinearProgressIndicator(progress = { if (p.total == 0) 1f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.batch_progress, p.done, p.total, p.joined, p.skipped, p.pendingApproval, p.failed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Text(stringResource(R.string.saved_configs), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.saved_lists_note), style = MaterialTheme.typography.bodySmall, color = Muted)
            OutlinedTextField(
                value = configName,
                onValueChange = { configName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.config_name_hint)) },
                singleLine = true,
            )
            Button(
                enabled = selected.isNotEmpty() && validateConfigName(configName),
                onClick = {
                    vm.saveJoinConfiguration(JoinConfiguration(configName.trim(), candidates.filter { it.name in selected }))
                    configName = ""
                },
            ) { Text(stringResource(R.string.save_config)) }
        }
        if (configs.isEmpty()) {
            item { Text(stringResource(R.string.no_saved_configs), color = Muted) }
        }
        items(configs, key = { "cfg-" + it.name }) { cfg ->
            Card(colors = CardDefaults.cardColors(containerColor = Raised)) {
                Column(Modifier.padding(12.dp)) {
                    Text(cfg.name, style = MaterialTheme.typography.titleSmall)
                    Text(cfg.networks.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            cfg.networks.forEach { n ->
                                if (added.none { it.name == n.name }) added.add(n)
                                privateFlags[n.name] = n.private
                            }
                            selected = cfg.networks.map { it.name }.toSet()
                            configName = cfg.name
                        }) { Text(stringResource(R.string.load_config)) }
                        TextButton(onClick = { vm.deleteJoinConfiguration(cfg.name) }) { Text(stringResource(R.string.delete_config)) }
                    }
                }
            }
        }
        item { Text("", Modifier.padding(bottom = 8.dp)) }
    }

    askPasswords?.let { nets ->
        PasswordsDialog(
            networks = nets.filter { it.private && it.name !in joinedNames },
            onDismiss = { askPasswords = null },
            onSubmit = { passwords ->
                vm.runBatchJoin(nets, passwords)
                askPasswords = null
            },
        )
    }
}

/** Private passwords are requested for each batch, held in memory only and zeroed after use. */
@Composable
private fun PasswordsDialog(networks: List<JoinNetwork>, onDismiss: () -> Unit, onSubmit: (Map<String, CharArray>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    val valid = networks.all { validateNetworkPassword(values[it.name].orEmpty()) }
    AlertDialog(
        onDismissRequest = {
            values.clear()
            onDismiss()
        },
        title = { Text(stringResource(R.string.private_passwords_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.private_passwords_desc), style = MaterialTheme.typography.bodySmall) }
                items(networks, key = { it.name }) { net ->
                    OutlinedTextField(
                        value = values[net.name].orEmpty(),
                        onValueChange = { values[net.name] = it },
                        label = { Text(net.name) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val map = networks.associate { it.name to values[it.name].orEmpty().toCharArray() }
                values.clear()
                onSubmit(map)
            }) { Text(stringResource(R.string.start_join)) }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                values.clear()
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
