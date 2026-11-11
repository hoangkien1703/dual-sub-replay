package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AI_PREFERENCES_FILE
import com.kienhoang.dualsubreplay.data.APP_PREFERENCES_FILE
import com.kienhoang.dualsubreplay.data.BackupContents
import com.kienhoang.dualsubreplay.data.DECK_PREFERENCES_FILE
import com.kienhoang.dualsubreplay.data.ImmersionRepository
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.MyKanjiStories
import com.kienhoang.dualsubreplay.data.ProgressRestore
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.WordRestore
import com.kienhoang.dualsubreplay.data.changedSettingKeys
import com.kienhoang.dualsubreplay.data.decodeSetupCode
import com.kienhoang.dualsubreplay.data.decodeUndo
import com.kienhoang.dualsubreplay.data.encodeBackup
import com.kienhoang.dualsubreplay.data.encodeSetupLink
import com.kienhoang.dualsubreplay.data.encodeUndo
import com.kienhoang.dualsubreplay.data.exportSettings
import com.kienhoang.dualsubreplay.data.myHanziStories
import com.kienhoang.dualsubreplay.data.parseBackup
import com.kienhoang.dualsubreplay.data.planProgressRestore
import com.kienhoang.dualsubreplay.data.planWordRestore
import com.kienhoang.dualsubreplay.data.readBackupText
import com.kienhoang.dualsubreplay.data.setupCodeIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.system.exitProcess

/** Backup time and the undo of the last shared setup; its own file, so neither travels in a backup. */
private const val BACKUP_STATE_PREFERENCES = "backup_sharing"
private const val LAST_BACKUP_KEY = "last_backup_at"
private const val SETUP_UNDO_KEY = "setup_undo"

/** A setup link opened from outside the app (the landing site or a chat), waiting for the user's answer. */
internal object SetupLinkInbox {
    var pendingCode by mutableStateOf<String?>(null)

    /** True when [text] was a setup link; the app then asks before using it. */
    fun offer(text: String): Boolean {
        val code = setupCodeIn(text) ?: return false
        pendingCode = code
        return true
    }
}

/** Which parts a backup holds or a restore takes. */
internal data class BackupParts(
    val settings: Boolean = true,
    val words: Boolean = true,
    val progress: Boolean = true,
)

/** A backup file read and checked, with what restoring it would do. */
internal data class RestorePlan(
    val contents: BackupContents,
    val words: WordRestore?,
    val progress: ProgressRestore?,
)

/** A shared setup ready to use: its values and the keys it changes. */
internal data class SetupPlan(
    val values: Map<String, Any>,
    val changed: List<String>,
)

private fun Context.preferenceMap(file: String): Map<String, *> = getSharedPreferences(file, Context.MODE_PRIVATE).all

/** Starts the app again from its first screen, so every screen reads the restored data. */
internal fun restartApp(context: Context) {
    val intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
    context.startActivity(intent)
    exitProcess(0)
}

@Stable
private class BackupController(
    private val context: Context,
    private val resources: Resources,
    private val scope: CoroutineScope,
) {
    private val state = context.getSharedPreferences(BACKUP_STATE_PREFERENCES, Context.MODE_PRIVATE)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var lastBackupMs by mutableStateOf(state.getLong(LAST_BACKUP_KEY, 0L))
    var canUndo by mutableStateOf(decodeUndo(state.getString(SETUP_UNDO_KEY, null)) != null)
    var choosing by mutableStateOf(false)
    var parts by mutableStateOf(BackupParts())
    var wordCount by mutableStateOf(0)
    var studyMs by mutableStateOf(0L)
    var restore by mutableStateOf<RestorePlan?>(null)
    var restoreParts by mutableStateOf(BackupParts())
    var pasting by mutableStateOf(false)

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = resources.getString(R.string.backup_failed, error.message ?: error.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun choose() =
        work {
            val words =
                VocabularyRepository
                    .get(context)
                    .also { it.refresh() }
                    .words.value
            val days = ImmersionRepository.get(context).allDays()
            wordCount = words.size
            studyMs = days.sumOf { it.ms }
            parts = BackupParts()
            message = null
            choosing = true
        }

    fun write(uri: Uri) =
        work {
            val chosen = parts
            val text =
                withContext(Dispatchers.IO) {
                    val words =
                        if (chosen.words) {
                            VocabularyRepository
                                .get(context)
                                .also { it.refresh() }
                                .words.value
                        } else {
                            null
                        }
                    val contents =
                        BackupContents(
                            createdAtMs = System.currentTimeMillis(),
                            appVersion = BuildConfig.VERSION_NAME,
                            settings =
                                if (chosen.settings) {
                                    listOf(APP_PREFERENCES_FILE, AI_PREFERENCES_FILE, DECK_PREFERENCES_FILE).associateWith { file ->
                                        exportSettings(file, context.preferenceMap(file), shared = false)
                                    }
                                } else {
                                    null
                                },
                            words = words,
                            knownWords = if (chosen.words) KnownWordsRepository.get(context).allKnown() else emptyMap(),
                            stories =
                                if (chosen.words) {
                                    mapOf(
                                        "ja" to MyKanjiStories.inDirectory(context.filesDir).all(),
                                        "zh" to myHanziStories(context.filesDir).all(),
                                    ).filterValues { it.isNotEmpty() }
                                } else {
                                    emptyMap()
                                },
                            progress = if (chosen.progress) ImmersionRepository.get(context).allDays() else null,
                        )
                    encodeBackup(contents)
                }
            withContext(Dispatchers.IO) {
                context.contentResolver
                    .openOutputStream(uri, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { it.write(text) }
                    ?: error(resources.getString(R.string.backup_open_failed))
            }
            val now = System.currentTimeMillis()
            state.edit().putLong(LAST_BACKUP_KEY, now).apply()
            lastBackupMs = now
            message = resources.getString(R.string.backup_saved)
        }

    fun read(uri: Uri) =
        work {
            message = null
            val contents =
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { parseBackup(readBackupText(it)) }
                        ?: error(resources.getString(R.string.backup_open_failed))
                }
            val words =
                contents.words?.let {
                    planWordRestore(
                        VocabularyRepository
                            .get(context)
                            .also { it.refresh() }
                            .words.value,
                        it,
                    )
                }
            val progress = contents.progress?.let { planProgressRestore(ImmersionRepository.get(context).allDays(), it) }
            val plan = RestorePlan(contents, words, progress)
            if (contents.settings.isNullOrEmpty() && contents.words.isNullOrEmpty() && contents.progress.isNullOrEmpty()) {
                message = resources.getString(R.string.restore_nothing)
            } else {
                restoreParts =
                    BackupParts(
                        settings = !contents.settings.isNullOrEmpty(),
                        words = !contents.words.isNullOrEmpty(),
                        progress = !contents.progress.isNullOrEmpty(),
                    )
                restore = plan
            }
        }

    fun restore() =
        work {
            val plan = restore ?: return@work
            val chosen = restoreParts
            restore = null
            if (chosen.words) {
                plan.words?.let { VocabularyRepository.get(context).importWords(it.words, replace = true) }
                val known = KnownWordsRepository.get(context)
                plan.contents.knownWords.forEach { (language, keys) -> known.setKnown(language, keys, known = true) }
                withContext(Dispatchers.IO) {
                    plan.contents.stories["ja"]?.let { restoreStories(MyKanjiStories.inDirectory(context.filesDir), it) }
                    plan.contents.stories["zh"]?.let { restoreStories(myHanziStories(context.filesDir), it) }
                }
            }
            if (chosen.progress) plan.progress?.let { ImmersionRepository.get(context).mergeDays(it.days) }
            if (chosen.settings) {
                withContext(Dispatchers.IO) {
                    plan.contents.settings?.forEach { (file, values) ->
                        writeSettings(context, file, values)
                    }
                }
            }
            restartApp(context)
        }

    fun share() {
        val values = exportSettings(APP_PREFERENCES_FILE, context.preferenceMap(APP_PREFERENCES_FILE), shared = true)
        val link = encodeSetupLink(values)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, resources.getString(R.string.share_setup_text, link))
            }
        context.startActivity(
            Intent.createChooser(send, resources.getString(R.string.share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun undo() =
        work {
            val previous = decodeUndo(state.getString(SETUP_UNDO_KEY, null)) ?: return@work
            withContext(Dispatchers.IO) {
                writeSettings(context, APP_PREFERENCES_FILE, previous)
                state.edit().remove(SETUP_UNDO_KEY).commit()
            }
            canUndo = false
            restartApp(context)
        }
}

/** Adds the backup's stories for characters that have none yet; a story written on this phone is kept. */
private fun restoreStories(
    target: MyKanjiStories,
    stories: Map<String, String>,
) {
    val existing = target.all()
    stories.filterKeys { it !in existing }.forEach { (literal, story) -> target.set(literal, story) }
}

/**
 * Writes [values] into preference [file] at once (commit, since the app restarts next). A null value
 * removes the key, which is how an undo puts back a setting that was never set.
 */
private fun writeSettings(
    context: Context,
    file: String,
    values: Map<String, Any?>,
) {
    val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
    values.forEach { (key, value) ->
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
        }
    }
    editor.commit()
}

/** Uses a shared setup: keeps the old values of what it changes for Undo, writes it, and restarts. */
internal fun useSetup(
    context: Context,
    plan: SetupPlan,
) {
    val current = context.preferenceMap(APP_PREFERENCES_FILE)
    val previous = plan.changed.associateWith { current[it] }
    context
        .getSharedPreferences(BACKUP_STATE_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(SETUP_UNDO_KEY, encodeUndo(previous))
        .commit()
    writeSettings(context, APP_PREFERENCES_FILE, plan.changed.associateWith { plan.values.getValue(it) })
    restartApp(context)
}

/** What a setup [code] would change on this phone; throws when the code is broken. */
internal fun planSetup(
    context: Context,
    code: String,
): SetupPlan {
    val values = decodeSetupCode(code)
    return SetupPlan(values, changedSettingKeys(context.preferenceMap(APP_PREFERENCES_FILE), values))
}

/** Settings → Backup & sharing (owner pick 2026-11-10). */
@Composable
internal fun BackupSharingSettingsSection() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, resources, scope) { BackupController(context, resources, scope) }
    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(controller::write)
        }
    val opener =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(controller::read)
        }
    BackupCard(
        title = stringResource(R.string.backup_title),
        description = stringResource(R.string.backup_description),
        primary = stringResource(R.string.backup_button) to controller::choose,
        secondary =
            stringResource(R.string.restore_button) to { opener.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
        enabled = !controller.busy,
        tag = "backup",
    ) {
        Text(
            if (controller.lastBackupMs > 0) {
                stringResource(
                    R.string.backup_last,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(controller.lastBackupMs)),
                )
            } else {
                stringResource(R.string.backup_last_never)
            },
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))
    BackupCard(
        title = stringResource(R.string.share_title),
        description = stringResource(R.string.share_description),
        primary = stringResource(R.string.share_button) to controller::share,
        secondary = stringResource(R.string.use_setup_button) to { controller.pasting = true },
        enabled = !controller.busy,
        tag = "share_setup",
    ) {
        if (controller.canUndo) {
            TextButton(onClick = controller::undo, enabled = !controller.busy, modifier = Modifier.testTag("undo_setup")) {
                Text(stringResource(R.string.undo_setup_button))
            }
        }
    }
    controller.message?.let { SettingsHint(it) }

    if (controller.choosing) {
        BackupChoiceDialog(
            parts = controller.parts,
            onPartsChange = { controller.parts = it },
            wordCount = controller.wordCount,
            studyMs = controller.studyMs,
            onDismiss = { controller.choosing = false },
            onSave = {
                controller.choosing = false
                saver.launch("DualSub-Backup-${java.time.LocalDate.now()}.json")
            },
        )
    }
    controller.restore?.let { plan ->
        RestoreDialog(
            plan = plan,
            parts = controller.restoreParts,
            onPartsChange = { controller.restoreParts = it },
            onDismiss = { controller.restore = null },
            onRestore = controller::restore,
        )
    }
    if (controller.pasting) {
        PasteSetupDialog(
            onDismiss = { controller.pasting = false },
            onCode = { code ->
                controller.pasting = false
                SetupLinkInbox.pendingCode = code
            },
        )
    }
}

@Composable
private fun BackupCard(
    title: String,
    description: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    enabled: Boolean,
    tag: String,
    footer: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = primary.second, enabled = enabled, modifier = Modifier.weight(1f).testTag("${tag}_primary")) {
                    Text(primary.first)
                }
                OutlinedButton(onClick = secondary.second, enabled = enabled, modifier = Modifier.weight(1f).testTag("${tag}_secondary")) {
                    Text(secondary.first)
                }
            }
            footer()
        }
    }
}

@Composable
private fun PartRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, modifier = Modifier.testTag(tag))
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoteBox(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupChoiceDialog(
    parts: BackupParts,
    onPartsChange: (BackupParts) -> Unit,
    wordCount: Int,
    studyMs: Long,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.backup_title), onClose = onDismiss) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.backup_dialog_message))
                PartRow(
                    stringResource(R.string.backup_part_settings),
                    stringResource(R.string.backup_part_settings_detail),
                    parts.settings,
                    onCheckedChange = { onPartsChange(parts.copy(settings = it)) },
                    tag = "backup_part_settings",
                )
                PartRow(
                    stringResource(R.string.backup_part_words),
                    pluralStringResource(R.plurals.backup_part_words_detail, wordCount, wordCount),
                    parts.words,
                    onCheckedChange = { onPartsChange(parts.copy(words = it)) },
                    tag = "backup_part_words",
                )
                PartRow(
                    stringResource(R.string.backup_part_progress),
                    stringResource(R.string.backup_part_progress_detail, immersionDurationText(studyMs)),
                    parts.progress,
                    onCheckedChange = { onPartsChange(parts.copy(progress = it)) },
                    tag = "backup_part_progress",
                )
                NoteBox(stringResource(R.string.backup_never_included))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = parts.settings || parts.words || parts.progress,
                modifier = Modifier.testTag("backup_save"),
            ) { Text(stringResource(R.string.backup_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
internal fun RestoreDialog(
    plan: RestorePlan,
    parts: BackupParts,
    onPartsChange: (BackupParts) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    val contents = plan.contents
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.restore_dialog_title), onClose = onDismiss) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (contents.createdAtMs > 0) {
                    Text(
                        stringResource(
                            R.string.restore_from,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(contents.createdAtMs)),
                            contents.appVersion,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!contents.settings.isNullOrEmpty()) {
                    PartRow(
                        stringResource(R.string.backup_part_settings),
                        stringResource(R.string.restore_settings_detail),
                        parts.settings,
                        onCheckedChange = { onPartsChange(parts.copy(settings = it)) },
                        tag = "restore_part_settings",
                    )
                }
                plan.words?.takeIf { !contents.words.isNullOrEmpty() }?.let { words ->
                    PartRow(
                        stringResource(R.string.backup_part_words),
                        pluralStringResource(R.plurals.restore_words_detail, words.added, words.added),
                        parts.words,
                        onCheckedChange = { onPartsChange(parts.copy(words = it)) },
                        tag = "restore_part_words",
                    )
                }
                plan.progress?.takeIf { !contents.progress.isNullOrEmpty() }?.let { progress ->
                    PartRow(
                        stringResource(R.string.backup_part_progress),
                        pluralStringResource(R.plurals.restore_progress_detail, progress.newDays, progress.newDays),
                        parts.progress,
                        onCheckedChange = { onPartsChange(parts.copy(progress = it)) },
                        tag = "restore_part_progress",
                    )
                }
                NoteBox(stringResource(R.string.restore_keys_note) + " " + stringResource(R.string.restore_restart_note))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onRestore,
                enabled = parts.settings || parts.words || parts.progress,
                modifier = Modifier.testTag("restore_confirm"),
            ) { Text(stringResource(R.string.restore_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun PasteSetupDialog(
    onDismiss: () -> Unit,
    onCode: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.use_setup_button), onClose = onDismiss) },
        text = {
            Column {
                Text(stringResource(R.string.paste_setup_message))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.paste_setup_label)) },
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.setup_link_invalid)) }) else null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("paste_setup_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val code = setupCodeIn(text)
                    if (code == null) invalid = true else onCode(code)
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("paste_setup_continue"),
            ) { Text(stringResource(R.string.paste_setup_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

/** The names of the settings a shared setup changes, as Search settings names them; the rest are "Other settings". */
@Composable
private fun changedSettingNames(keys: List<String>): List<String> {
    val named = keys.mapNotNull { SearchableSetting.forKey(it) }.distinct().map { stringResource(it.titleRes) }
    val other = keys.any { SearchableSetting.forKey(it) == null }
    return if (other) named + stringResource(R.string.use_setup_other) else named
}

private const val SETUP_NAMES_SHOWN = 6

/** Asks before using a shared setup that came from a link (the landing site, a chat, or Use a setup). */
@Composable
internal fun PendingSetupDialog() {
    val code = SetupLinkInbox.pendingCode ?: return
    val context = LocalContext.current
    val plan = remember(code) { runCatching { planSetup(context, code) }.getOrNull() }
    val dismiss = { SetupLinkInbox.pendingCode = null }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { DialogTitle(stringResource(R.string.use_setup_title), onClose = dismiss) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    plan == null -> Text(stringResource(R.string.setup_link_invalid))
                    plan.changed.isEmpty() -> Text(stringResource(R.string.use_setup_no_changes))
                    else -> {
                        val names = changedSettingNames(plan.changed)
                        Text(
                            pluralStringResource(R.plurals.use_setup_changes, plan.changed.size, plan.changed.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        names.take(SETUP_NAMES_SHOWN).forEach { name ->
                            Text(name, modifier = Modifier.padding(vertical = 6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        val hidden = names.size - SETUP_NAMES_SHOWN
                        if (hidden > 0) {
                            Text(
                                pluralStringResource(R.plurals.use_setup_more, hidden, hidden),
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NoteBox(stringResource(R.string.use_setup_note))
                    }
                }
            }
        },
        confirmButton = {
            if (plan != null && plan.changed.isNotEmpty()) {
                TextButton(
                    onClick = {
                        SetupLinkInbox.pendingCode = null
                        useSetup(context, plan)
                    },
                    modifier = Modifier.testTag("use_setup_confirm"),
                ) { Text(stringResource(R.string.use_setup_confirm)) }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}
