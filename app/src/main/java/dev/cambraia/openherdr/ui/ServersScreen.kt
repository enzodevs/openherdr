package dev.cambraia.openherdr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cambraia.openherdr.data.ServerProfile

/** Servers tab: every saved server; tapping one connects, the pencil edits it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServersTab(
    profiles: List<ServerProfile>,
    liveId: String?,
    bottomInset: Dp,
    onConnect: (ServerProfile) -> Unit,
    onEdit: (ServerProfile?) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            ScreenHeader("Servers", subtitle = "Machines running herdr", inset = 8.dp) {
                FilledIconButton(onClick = { onEdit(null) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Add, contentDescription = "Add server")
                }
            }
        }
        if (profiles.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    title = "No servers yet",
                    body = "Add the machine where herdr runs. OpenHerdr talks to herdr over SSH; nothing is installed on the server.",
                    modifier = Modifier.padding(top = 48.dp),
                ) { Button(onClick = { onEdit(null) }) { Text("Add server") } }
            }
        }
        items(profiles, key = { it.id }) { profile ->
            ServerCard(
                profile,
                live = profile.id == liveId,
                onClick = { onConnect(profile) },
                modifier = Modifier.animateItem(),
                trailing = {
                    IconButton(onClick = { onEdit(profile) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.name}", tint = Graphite.muted)
                    }
                },
            )
        }
        if (profiles.isNotEmpty()) {
            item(key = "hint") {
                Text(
                    "Away from home, reach the server through WireGuard or Tailscale instead of exposing SSH.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Graphite.faint,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }
}
