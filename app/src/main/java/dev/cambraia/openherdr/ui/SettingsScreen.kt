package dev.cambraia.openherdr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cambraia.openherdr.voice.SpeechModel

data class SettingsInfo(
    val keyFingerprint: String?,
    val keyProtection: String?,
    val voiceInstalled: Boolean,
    val voicePath: String,
    val version: String,
)

/** Settings tab: device key, voice dictation and what the app does (and does not) do. */
@Composable
fun SettingsTab(info: SettingsInfo, bottomInset: Dp, onDeviceKey: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader("Settings", inset = 8.dp) }

        item { SectionLabel("Security") }
        item {
            Panel(Modifier.fillMaxWidth(), onClick = onDeviceKey) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Leading(Glyphs.Key)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text("Device key", style = MaterialTheme.typography.titleMedium)
                        Text(
                            info.keyFingerprint ?: "Creating…",
                            fontFamily = Mono, fontSize = 12.sp, color = Graphite.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        info.keyProtection?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Graphite.faint) }
                    }
                    Icon(Glyphs.Chevron, contentDescription = null, tint = Graphite.faint)
                }
            }
        }

        item { SectionLabel("Voice") }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Leading(Glyphs.Mic)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dictation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Dot(if (info.voiceInstalled) Graphite.done else Graphite.faint, 5.dp)
                            Text(
                                if (info.voiceInstalled) "Ready" else "Model missing",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (info.voiceInstalled) Graphite.done else Graphite.muted,
                            )
                        }
                        Text(
                            "NVIDIA Parakeet TDT 0.6B v3 runs on this phone. Speak in Portuguese, English or 23 other European languages; audio never leaves the device.",
                            style = MaterialTheme.typography.bodySmall, color = Graphite.muted, modifier = Modifier.padding(top = 4.dp),
                        )
                        if (!info.voiceInstalled) {
                            Text(
                                "Copy the ${SpeechModel.NAME} files (encoder, decoder, joiner, tokens) to:",
                                style = MaterialTheme.typography.bodySmall, color = Graphite.muted, modifier = Modifier.padding(top = 10.dp),
                            )
                            Fingerprint(info.voicePath)
                        }
                    }
                }
            }
        }

        item { SectionLabel("About") }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("OpenHerdr ${info.version}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No accounts, no telemetry, no cloud. Talks only to your servers over SSH.",
                        style = MaterialTheme.typography.bodySmall, color = Graphite.muted,
                    )
                    Text(
                        "Apache-2.0. Unbounded and Geist fonts (OFL), sherpa-onnx (Apache-2.0), Backdrop (Apache-2.0), Parakeet model (CC-BY-4.0, NVIDIA). Not affiliated with herdr.",
                        style = MaterialTheme.typography.bodySmall, color = Graphite.faint,
                    )
                }
            }
        }
    }
}

@Composable
private fun Leading(icon: ImageVector) {
    androidx.compose.material3.Surface(color = Graphite.high, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(44.dp)) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Graphite.text, modifier = Modifier.size(22.dp))
        }
    }
}
