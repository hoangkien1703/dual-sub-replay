package com.kienhoang.dualsubreplay.ui

import android.content.ContentResolver
import android.content.res.Resources
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.*
import kotlinx.coroutines.*

@Stable
private class PracticeTransferController(
    private val resolver: ContentResolver,
    /** The activity's resources, which show the chosen interface language. */
    private val resources: Resources,
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
                    message = error.message ?: resources.getString(R.string.practice_transfer_failed)
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
                        ?: error(resources.getString(R.string.practice_transfer_open_failed))
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
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_TRANSFER_BYTES) {
                resources.getString(R.string.practice_transfer_export_too_large)
            }
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: error(resources.getString(R.string.practice_transfer_write_failed))
        }
        message = resources.getQuantityString(R.plurals.practice_transfer_exported, snapshot.size, snapshot.size)
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
                message = resources.getQuantityString(R.plurals.practice_transfer_imported, count, count)
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
    val context = LocalContext.current
    val resolver = context.contentResolver
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val controller =
        remember(resolver, resources, repository, scope) { PracticeTransferController(resolver, resources, repository, scope) }
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
        TextButton(enabled = !controller.busy, onClick = { importer.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.practice_transfer_import))
        }
        TextButton(enabled = !controller.busy, onClick = { exportChoice = true }) {
            Text(stringResource(R.string.practice_transfer_export))
        }
        if (controller.busy) TextButton(onClick = controller::cancel) { Text(stringResource(R.string.practice_transfer_cancel)) }
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
        title = {
            val title = if (preview == null) R.string.practice_import_review_title else R.string.practice_import_preview_title
            Text(stringResource(title))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview != null) {
                    Text(
                        stringResource(
                            R.string.practice_import_summary,
                            pluralStringResource(R.plurals.practice_import_valid_count, preview.words.size, preview.words.size),
                            pluralStringResource(R.plurals.practice_import_invalid_count, preview.invalid, preview.invalid),
                            pluralStringResource(R.plurals.practice_import_duplicate_count, preview.duplicates, preview.duplicates),
                        ),
                    )
                    Text(stringResource(R.string.practice_import_skip_note))
                    Row {
                        Checkbox(controller.replace, { controller.replace = it }, enabled = !controller.busy)
                        Text(stringResource(R.string.practice_import_replace_duplicates))
                    }
                } else if (selected.backup != null) {
                    Text(pluralStringResource(R.plurals.practice_import_backup_summary, selected.backup.size, selected.backup.size))
                } else {
                    ImportMappingForm(controller, selected)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !controller.busy && (preview == null || preview.words.isNotEmpty()), onClick = controller::advance) {
                val label = if (preview == null) R.string.practice_import_preview_button else R.string.practice_import_words_button
                Text(stringResource(label))
            }
        },
        dismissButton = { TextButton(onClick = controller::cancel) { Text(stringResource(R.string.practice_cancel)) } },
    )
}

@Composable
private fun ImportMappingForm(
    controller: PracticeTransferController,
    selected: TransferDocument,
) {
    Text(stringResource(R.string.practice_import_mapping_hint))
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
    val wordLanguageLabel = stringResource(R.string.practice_import_word_language_label)
    val meaningLanguageLabel = stringResource(R.string.practice_import_meaning_language_label)
    OutlinedTextField(controller.source, { controller.source = it }, label = { Text(wordLanguageLabel) })
    OutlinedTextField(controller.target, { controller.target = it }, label = { Text(meaningLanguageLabel) })
    selected.rows.firstOrNull()?.let { row ->
        Text(stringResource(R.string.practice_import_first_row, row.take(3).joinToString(" · ").take(160)))
    }
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
            val column =
                if (selected < 0) {
                    stringResource(R.string.practice_import_not_included)
                } else {
                    stringResource(R.string.practice_import_column_number, selected + 1)
                }
            Text(stringResource(R.string.practice_import_column_mapping, name, column))
        }
        DropdownMenu(expanded, { expanded = false }) {
            (-1 until count).forEach { index ->
                DropdownMenuItem(text = {
                    Text(
                        if (index < 0) {
                            stringResource(R.string.practice_import_not_included)
                        } else {
                            stringResource(R.string.practice_import_column_option, index + 1, headers.getOrNull(index).orEmpty())
                        },
                    )
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
        title = { Text(stringResource(R.string.practice_export_title)) },
        text = {
            Column {
                Text(stringResource(R.string.practice_export_description))
                TextButton(onClick = { onExport(true) }) { Text(stringResource(R.string.practice_export_full_backup)) }
                TextButton(onClick = { onExport(false) }) { Text(stringResource(R.string.practice_export_anki_text)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.practice_cancel)) } },
    )
}
