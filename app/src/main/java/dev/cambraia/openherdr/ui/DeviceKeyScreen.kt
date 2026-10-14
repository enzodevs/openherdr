package dev.cambraia.openherdr.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cambraia.openherdr.data.DeviceKey
import kotlinx.coroutines.launch

data class DeviceKeyInfo(val authorizedKey: String, val fingerprint: String, val protection: DeviceKey.Protection)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceKeyScreen(info: DeviceKeyInfo?, onRegenerate: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var confirmRegenerate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device key") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Add this public key to ~/.ssh/authorized_keys on each server. The private key was generated inside this phone's secure hardware and cannot be copied out.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (info == null) {
                Text("Creating key…")
                return@Column
            }
            Fingerprint(info.authorizedKey)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OpenHerdr public key", info.authorizedKey))) } },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
                FilledTonalButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, info.authorizedKey)
                        context.startActivity(Intent.createChooser(send, "Share public key"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("  Share")
                }
            }
            Text("Fingerprint", style = MaterialTheme.typography.titleSmall)
            Fingerprint(info.fingerprint)
            Text("Protection", style = MaterialTheme.typography.titleSmall)
            Text(
                when (info.protection) {
                    DeviceKey.Protection.StrongBox -> "StrongBox secure element"
                    DeviceKey.Protection.TrustedEnvironment -> "Trusted Execution Environment"
                    DeviceKey.Protection.Software -> "Software only (this device has no secure key hardware)"
                    DeviceKey.Protection.Unknown -> "Unknown"
                },
            )
            TextButton(onClick = { confirmRegenerate = true }) { Text("Create a new key…") }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Replace the device key?") },
            text = { Text("The current key is deleted. You must add the new public key to every server again.") },
            confirmButton = { TextButton(onClick = { confirmRegenerate = false; onRegenerate() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
        )
    }
}
