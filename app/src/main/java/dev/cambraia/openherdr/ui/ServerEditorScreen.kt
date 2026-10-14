package dev.cambraia.openherdr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cambraia.openherdr.data.ServerProfile
import dev.cambraia.openherdr.ssh.HostKeyPin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(
    existing: ServerProfile?,
    pinned: HostKeyPin?,
    onSave: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onForgetHostKey: (ServerProfile) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(existing?.port?.toString() ?: "22") }
    var user by rememberSaveable { mutableStateOf(existing?.user ?: "") }
    var session by rememberSaveable { mutableStateOf(existing?.herdrSession ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val problem = ServerProfile.validate(host, port, user, session)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add server" else "Edit server") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("Home PC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                host, { host = it }, label = { Text("Host") }, placeholder = { Text("10.13.13.1") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("SSH port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                user, { user = it }, label = { Text("User") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                session, { session = it }, label = { Text("herdr session (optional)") },
                supportingText = { Text("Leave empty for the default session") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            problem?.takeIf { host.isNotEmpty() || user.isNotEmpty() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = problem == null,
                onClick = {
                    val base = existing ?: ServerProfile(name = "", host = "", user = "")
                    onSave(
                        base.copy(
                            name = name.trim().ifEmpty { host.trim() },
                            host = host.trim(),
                            port = port.toInt(),
                            user = user.trim(),
                            herdrSession = session.trim().ifEmpty { null },
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            if (existing != null && pinned != null) {
                Text("Trusted host key", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Fingerprint("${pinned.keyType}\n${pinned.fingerprint}")
                OutlinedButton(onClick = { onForgetHostKey(existing) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Forget host key")
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            confirmButton = { TextButton(onClick = { onDelete(existing) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
