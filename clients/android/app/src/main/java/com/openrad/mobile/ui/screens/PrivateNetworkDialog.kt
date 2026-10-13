package com.openrad.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.openrad.mobile.R
import com.openrad.mobile.util.validateNetworkName
import com.openrad.mobile.util.validateNetworkPassword

/** Create/join a private network. Mirrors `network_ui.rs`; the password is handed over as a CharArray and cleared. */
@Composable
fun PrivateNetworkDialog(
    create: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, CharArray) -> Unit,
    initialName: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = {
            password = ""
            confirmation = ""
            onDismiss()
        },
        title = { Text(stringResource(if (create) R.string.dialog_create_title else R.string.dialog_join_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (create) R.string.dialog_create_desc else R.string.dialog_join_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.network_name)) },
                    singleLine = true,
                )
                val transformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (create) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(R.string.confirm_password)) },
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showPassword, onCheckedChange = { showPassword = it })
                    Text(stringResource(R.string.show_password))
                }
                Text(stringResource(R.string.password_hint), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = when {
                    !validateNetworkName(name) -> R.string.name_invalid
                    !validateNetworkPassword(password) -> R.string.password_invalid
                    create && password != confirmation -> R.string.passwords_no_match
                    else -> null
                }
                if (error == null) {
                    onSubmit(name, password.toCharArray())
                    password = ""
                    confirmation = ""
                }
            }) {
                Text(stringResource(if (create) R.string.create_network else R.string.join_network))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                password = ""
                confirmation = ""
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
