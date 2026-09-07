package com.kienhoang.dualsubreplay.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.*
import kotlinx.coroutines.*

@Stable
private class PracticeTransferController(
    private val resolver: ContentResolver,
    private val repository: VocabularyRepository,
    private val scope: CoroutineScope,
) {
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var document by mutableStateOf<TransferDocument?>(null)
    var mapping by mutableStateOf<Map<String, Int>>(emptyMap())
    var source by mutableStateOf("en")
    var target by mutableStateOf("vi")
    var preview by mutableStateOf<ImportPreview?>(null)
    var replace by mutableStateOf(false)
    private var job: Job? = null

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        job =
            scope.launch {
                try {
                    block()
                } catch (
                    cancel: CancellationException,
                ) {
                    throw cancel
                } catch (
                    error: Exception,
                ) {
                    message = error.message ?: "Transfer failed. Please retry."
                } finally {
                    busy = false
                }
            }
    }

    fun read(uri: Uri) =
        work {
            val parsed =
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { parseTransfer(readTransferText(it)) }
                        ?: error("Could not open the selected file.")
                }
            document = parsed
            mapping = defaultTransferMapping(parsed)
            preview = null
            replace = false
        }

    fun write(
        uri: Uri,
        words: List<SavedWord>,
        backup: Boolean,
    ) = work {
        val snapshot = words.toList()
        withContext(Dispatchers.IO) {
            val text = if (backup) exportBackup(snapshot) else exportAnkiTsv(snapshot)
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_TRANSFER_BYTES) { "Export exceeds the 8 MiB import limit." }
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: error("Could not write to the selected file.")
        }
        message = "Exported ${snapshot.size} words."
    }

    fun advance() =
        work {
            val result = preview
            if (result == null) {
                val selected = document ?: return@work
                val columns = mapping
                val sourceLanguage = source
                val targetLanguage = target
                val existing = repository.words.value
                preview =
                    withContext(Dispatchers.Default) {
                        previewImport(selected, columns, sourceLanguage, targetLanguage, existing)
                    }
            } else {
                val count = repository.importWords(result.words, replace, preserveReviewHistory = document?.backup == null)
                document = null
                preview = null
                message = "Imported $count words."
            }
        }

    fun cancel() {
        job?.cancel()
        document = null
        preview = null
    }
}

@Composable
internal fun PracticeTransferControls(
    repository: VocabularyRepository,
    words: List<SavedWord>,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val controller = remember(resolver, repository, scope) { PracticeTransferController(resolver, repository, scope) }
    var exportChoice by remember { mutableStateOf(false) }
    var exportBackupFormat by rememberSaveable { mutableStateOf(true) }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(controller::read)
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let { controller.write(it, words, exportBackupFormat) }
        }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !controller.busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import") }
        TextButton(enabled = !controller.busy, onClick = { exportChoice = true }) { Text("Export") }
        if (controller.busy) TextButton(onClick = controller::cancel) { Text("Cancel transfer") }
    }
    controller.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (exportChoice) {
        ExportPracticeDialog(
            onDismiss = { exportChoice = false },
            onExport = { backup ->
                exportChoice = false
                exportBackupFormat = backup
                exporter.launch(if (backup) "DualSub-Practice.json" else "DualSub-Anki.tsv")
            },
        )
    }
    controller.document?.let { ImportPracticeDialog(controller, it) }
}

@Composable
private fun ImportPracticeDialog(
    controller: PracticeTransferController,
    selected: TransferDocument,
) {
    val preview = controller.preview
    AlertDialog(
        onDismissRequest = controller::cancel,
        title = { Text(if (preview == null) "Review import" else "Import preview") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview != null) {
                    Text("${preview.words.size} valid · ${preview.invalid} invalid · ${preview.duplicates} duplicates")
                    Text("Invalid records will be skipped. Existing words are kept unless you select replacement.")
                    Row {
                        Checkbox(controller.replace, { controller.replace = it }, enabled = !controller.busy)
                        Text("Replace duplicates, including backup review history")
                    }
                } else if (selected.backup != null) {
                    Text("Full backup: ${selected.backup.size} readable words. Review history is included.")
                } else {
                    ImportMappingForm(controller, selected)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !controller.busy && (preview == null || preview.words.isNotEmpty()), onClick = controller::advance) {
                Text(if (preview == null) "Preview" else "Import words")
            }
        },
        dismissButton = { TextButton(onClick = controller::cancel) { Text("Cancel") } },
    )
}

@Composable
private fun ImportMappingForm(
    controller: PracticeTransferController,
    selected: TransferDocument,
) {
    Text("Map columns to fields. Anki text does not include review scheduling or media.")
    transferColumns.forEach { field ->
        TransferColumnPicker(
            field,
            controller.mapping[field] ?: -1,
            selected.rows.maxOfOrNull { it.size } ?: 2,
            selected.columns,
        ) { column ->
            controller.mapping = controller.mapping + (field to column)
        }
    }
    OutlinedTextField(controller.source, { controller.source = it }, label = { Text("Default word language (en, ja…)") })
    OutlinedTextField(controller.target, { controller.target = it }, label = { Text("Default meaning language (vi, en…)") })
    selected.rows.firstOrNull()?.let { Text("First row: ${it.take(3).joinToString(" · ").take(160)}") }
}

@Composable
private fun TransferColumnPicker(
    name: String,
    selected: Int,
    count: Int,
    headers: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("$name: ${if (selected < 0) "Not included" else "Column ${selected + 1}"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            (-1 until count).forEach { index ->
                DropdownMenuItem(text = {
                    Text(if (index < 0) "Not included" else "${index + 1}: ${headers.getOrNull(index).orEmpty()}")
                }, onClick = {
                    expanded = false
                    onSelect(index)
                })
            }
        }
    }
}

@Composable
private fun ExportPracticeDialog(
    onDismiss: () -> Unit,
    onExport: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Practice") },
        text = {
            Column {
                Text(
                    "Backup preserves review history. Anki text transfers words and examples, " +
                        "without scheduling, media or card templates.",
                )
                TextButton(onClick = { onExport(true) }) { Text("Full backup (JSON)") }
                TextButton(onClick = { onExport(false) }) { Text("Anki text (TSV)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
