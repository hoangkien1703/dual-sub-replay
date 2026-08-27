package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.alignment.AcousticModelStatus
import com.kienhoang.dualsubreplay.alignment.OnDeviceCtcAligner
import com.kienhoang.dualsubreplay.data.KaraokeSyncDiagnostics
import com.kienhoang.dualsubreplay.data.KaraokeSyncMode
import com.kienhoang.dualsubreplay.data.KaraokeSyncPreferences
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Experimental A/B controls kept under More settings until one sync strategy wins. */
@Composable
internal fun ExperimentalKaraokeSyncSettings() {
    val context = LocalContext.current
    val aligner = remember(context) { OnDeviceCtcAligner(context) }
    val scope = rememberCoroutineScope()
    var selectedMode by remember { mutableStateOf(KaraokeSyncPreferences.mode(context)) }
    var highlightLeadMs by remember {
        mutableLongStateOf(KaraokeSyncPreferences.persistedHighlightLeadMs(context))
    }
    var diagnosticsEnabled by remember {
        mutableStateOf(KaraokeSyncPreferences.diagnosticsEnabled(context))
    }
    var acousticEnabled by remember {
        mutableStateOf(KaraokeSyncPreferences.acousticModelEnabled(context))
    }
    var modelStatus by remember { mutableStateOf(aligner.modelStatus()) }
    var modelBusy by remember { mutableStateOf(false) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    val diagnostics by KaraokeSyncDiagnostics.entries.collectAsState()

    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    Text("Experimental karaoke sync", style = MaterialTheme.typography.titleSmall)
    Text(
        "Compare synchronization strategies on the same difficult video before changing the default.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    KaraokeSyncModeOption(
        mode = KaraokeSyncMode.PR33_CURRENT,
        selected = selectedMode,
        title = "PR #33 current",
        description = "Hard YouTube anchors + acoustic alignment. Use this as the control.",
        onSelect = {
            selectedMode = it
            KaraokeSyncPreferences.setMode(context, it)
        },
    )
    KaraokeSyncModeOption(
        mode = KaraokeSyncMode.SOFT_ANCHOR,
        selected = selectedMode,
        title = "Soft YouTube anchors",
        description = "Acoustic timing may move generated-caption anchors by up to ±400 ms.",
        onSelect = {
            selectedMode = it
            KaraokeSyncPreferences.setMode(context, it)
        },
    )
    KaraokeSyncModeOption(
        mode = KaraokeSyncMode.ENHANCED,
        selected = selectedMode,
        title = "Enhanced acoustic sync",
        description = "Soft anchors + larger overlapping audio context + conservative silence trimming.",
        onSelect = {
            selectedMode = it
            KaraokeSyncPreferences.setMode(context, it)
        },
    )
    KaraokeSyncModeOption(
        mode = KaraokeSyncMode.ESTIMATED_ONLY,
        selected = selectedMode,
        title = "Estimated only",
        description = "Disable acoustic alignment. Useful as a baseline/control.",
        onSelect = {
            selectedMode = it
            KaraokeSyncPreferences.setMode(context, it)
        },
    )

    Spacer(Modifier.height(12.dp))
    Text("Highlight timing adjustment")
    Text(
        highlightLeadLabel(highlightLeadMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = highlightLeadMs.toFloat(),
        onValueChange = { raw ->
            val rounded = (raw / 10f).roundToInt() * 10L
            highlightLeadMs = rounded
            KaraokeSyncPreferences.setHighlightLeadMs(context, rounded)
        },
        valueRange = KaraokeSyncPreferences.MIN_HIGHLIGHT_LEAD_MS.toFloat()..
            KaraokeSyncPreferences.MAX_HIGHLIGHT_LEAD_MS.toFloat(),
        modifier = Modifier.fillMaxWidth().testTag("karaoke_sync_lead_slider"),
    )
    Text(
        "Try +20, +70, and +120 ms. If one fixed value solves the lag, the problem is mostly render latency rather than word alignment.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    Text("English acoustic model", style = MaterialTheme.typography.titleSmall)
    Text(
        "Optional Wav2Vec2 English model. It stays outside the APK and is downloaded only when you choose to install it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        modelStatusLabel(modelStatus),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Use acoustic sync")
            Text(
                if (modelStatus.installed) "English generated captions only for now." else "Install the model first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = acousticEnabled && modelStatus.installed,
            enabled = modelStatus.installed && !modelBusy,
            onCheckedChange = { enabled ->
                acousticEnabled = enabled
                KaraokeSyncPreferences.setAcousticModelEnabled(context, enabled)
            },
            modifier = Modifier.testTag("karaoke_acoustic_enabled"),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!modelStatus.installed) {
            OutlinedButton(
                enabled = !modelBusy,
                onClick = {
                    modelBusy = true
                    modelMessage = null
                    scope.launch {
                        runCatching { aligner.downloadModel() }
                            .onSuccess { status ->
                                modelStatus = status
                                acousticEnabled = true
                                modelMessage = "English acoustic model installed."
                            }
                            .onFailure { error ->
                                modelMessage = error.message ?: "Model download failed."
                            }
                        modelBusy = false
                    }
                },
                modifier = Modifier.testTag("download_acoustic_model"),
            ) {
                if (modelBusy) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Download ~95 MB")
                }
            }
        } else {
            OutlinedButton(
                enabled = !modelBusy,
                onClick = {
                    acousticEnabled = false
                    KaraokeSyncPreferences.setAcousticModelEnabled(context, false)
                    val deleted = aligner.deleteModel()
                    modelStatus = aligner.modelStatus()
                    modelMessage = if (deleted) "Acoustic model deleted." else "Could not delete the model."
                },
                modifier = Modifier.testTag("delete_acoustic_model"),
            ) {
                Text("Delete model")
            }
        }
    }
    modelMessage?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Show sync diagnostics")
            Text(
                "Shows recent original → acoustic → used word timestamps and also writes them to Logcat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = diagnosticsEnabled,
            onCheckedChange = { enabled ->
                diagnosticsEnabled = enabled
                KaraokeSyncPreferences.setDiagnosticsEnabled(context, enabled)
            },
            modifier = Modifier.testTag("karaoke_sync_diagnostics"),
        )
    }

    if (diagnosticsEnabled) {
        if (diagnostics.isEmpty()) {
            Text(
                "No acoustic timing samples yet. Play an eligible English auto-caption video with acoustic sync enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            diagnostics.takeLast(5).reversed().forEach { diagnostic ->
                val shift = diagnostic.usedStartMs - diagnostic.originalStartMs
                Text(
                    "${diagnostic.word}: ${diagnostic.originalSource} ${diagnostic.originalStartMs} → " +
                        "acoustic ${diagnostic.acousticStartMs} → used ${diagnostic.usedStartMs} ms " +
                        "(${signedMs(shift)})",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun KaraokeSyncModeOption(
    mode: KaraokeSyncMode,
    selected: KaraokeSyncMode,
    title: String,
    description: String,
    onSelect: (KaraokeSyncMode) -> Unit,
) {
    val isSelected = mode == selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(mode) }
            .padding(vertical = 5.dp)
            .testTag("karaoke_sync_mode_${mode.preferenceValue}"),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = isSelected, onClick = { onSelect(mode) })
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun highlightLeadLabel(value: Long): String = when {
    value > 0L -> "+$value ms · highlight earlier"
    value < 0L -> "$value ms · highlight later"
    else -> "0 ms · no render compensation"
}

private fun modelStatusLabel(status: AcousticModelStatus): String = when {
    status.installed && status.sizeBytes > 0L ->
        "Installed · ${status.sizeBytes / (1024 * 1024)} MB"
    status.installed -> "Installed"
    else -> "Not installed · download size ~95 MB"
}

private fun signedMs(value: Long): String = when {
    value > 0L -> "+$value ms"
    else -> "$value ms"
}
