package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Collator

/** English is built into both translation engines, so it is always on the device. */
internal const val BUILT_IN_LANGUAGE = "en"
internal const val JAPANESE_LANGUAGE = "ja"

/** The offline files of each translation language. */
internal interface LanguagePacks {
    /** The languages this build can download, without [BUILT_IN_LANGUAGE]. */
    suspend fun available(): List<String>

    suspend fun downloaded(): Set<String>

    suspend fun download(code: String)

    suspend fun remove(code: String)
}

/** Japanese also needs the word dictionary tap-to-learn uses; the other languages are just their [models]. */
internal class LanguagePacksWithJapaneseDictionary(
    private val models: LanguagePacks,
    private val dictionaryInstalled: () -> Boolean,
    private val installDictionary: () -> Boolean,
    private val removeDictionary: () -> Unit,
) : LanguagePacks {
    override suspend fun available(): List<String> = models.available()

    override suspend fun downloaded(): Set<String> {
        val downloaded = models.downloaded()
        return if (withContext(Dispatchers.IO) { dictionaryInstalled() }) downloaded else downloaded - JAPANESE_LANGUAGE
    }

    override suspend fun download(code: String) {
        models.download(code)
        if (code == JAPANESE_LANGUAGE && !withContext(Dispatchers.IO) { installDictionary() }) {
            throw IOException("The Japanese dictionary could not be downloaded.")
        }
    }

    override suspend fun remove(code: String) {
        models.remove(code)
        if (code == JAPANESE_LANGUAGE) withContext(Dispatchers.IO) { removeDictionary() }
    }
}

internal data class LanguageDownloadsState(
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val available: List<String> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val downloading: Set<String> = emptySet(),
    val removing: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
)

internal enum class LanguageDownloadStatus(
    @StringRes val labelRes: Int,
) {
    BUILT_IN(R.string.language_downloads_built_in),
    DOWNLOADED(R.string.language_downloads_downloaded),
    NOT_DOWNLOADED(R.string.language_downloads_not_downloaded),
    DOWNLOADING(R.string.language_downloads_downloading),
    REMOVING(R.string.language_downloads_removing),
    FAILED(R.string.language_downloads_failed),
}

internal data class LanguageDownloadRow(
    val code: String,
    val name: String,
    val status: LanguageDownloadStatus,
    val onDevice: Boolean,
)

internal data class LanguageDownloadGroups(
    val onDevice: List<LanguageDownloadRow>,
    val available: List<LanguageDownloadRow>,
)

/**
 * Splits the languages into those on the device (English first, as built in) and those that can be
 * downloaded, each sorted by [nameOf] with [order]. A language being downloaded stays in the second
 * group until it finishes; one being removed stays in the first.
 */
internal fun languageDownloadGroups(
    state: LanguageDownloadsState,
    nameOf: (String) -> String,
    order: Comparator<in String> = String.CASE_INSENSITIVE_ORDER,
): LanguageDownloadGroups {
    val rows =
        state.available
            .filter { it != BUILT_IN_LANGUAGE }
            .map { code ->
                val onDevice = code in state.downloaded
                val status =
                    when (code) {
                        in state.removing -> LanguageDownloadStatus.REMOVING
                        in state.downloading -> LanguageDownloadStatus.DOWNLOADING
                        in state.failed -> LanguageDownloadStatus.FAILED
                        in state.downloaded -> LanguageDownloadStatus.DOWNLOADED
                        else -> LanguageDownloadStatus.NOT_DOWNLOADED
                    }
                LanguageDownloadRow(code, nameOf(code), status, onDevice)
            }.sortedWith(compareBy(order, LanguageDownloadRow::name))
    val builtIn = LanguageDownloadRow(BUILT_IN_LANGUAGE, nameOf(BUILT_IN_LANGUAGE), LanguageDownloadStatus.BUILT_IN, onDevice = true)
    val (onDevice, available) = rows.partition(LanguageDownloadRow::onDevice)
    return LanguageDownloadGroups(listOf(builtIn) + onDevice, available)
}

/** Lists, downloads and removes languages. Work runs in [scope], so it continues after the screen closes. */
internal class LanguageDownloadsController(
    private val scope: CoroutineScope,
    private val packs: LanguagePacks,
) {
    private val mutableState = MutableStateFlow(LanguageDownloadsState())
    val state: StateFlow<LanguageDownloadsState> = mutableState
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        refreshJob =
            scope.launch {
                val loaded = attempt { packs.available() to packs.downloaded() }
                mutableState.update { current ->
                    if (loaded == null) {
                        current.copy(loading = false, loadFailed = true)
                    } else {
                        current.copy(loading = false, available = loaded.first, downloaded = loaded.second)
                    }
                }
            }
    }

    fun download(code: String) {
        if (isBusy(code)) return
        mutableState.update { it.copy(downloading = it.downloading + code, failed = it.failed - code) }
        scope.launch {
            val done = attempt { packs.download(code) } != null
            mutableState.update {
                it.copy(
                    downloading = it.downloading - code,
                    downloaded = if (done) it.downloaded + code else it.downloaded,
                    failed = if (done) it.failed else it.failed + code,
                )
            }
        }
    }

    fun remove(code: String) {
        if (isBusy(code)) return
        mutableState.update { it.copy(removing = it.removing + code, failed = it.failed - code) }
        scope.launch {
            val done = attempt { packs.remove(code) } != null
            mutableState.update {
                it.copy(
                    removing = it.removing - code,
                    downloaded = if (done) it.downloaded - code else it.downloaded,
                    failed = if (done) it.failed else it.failed + code,
                )
            }
        }
    }

    private fun isBusy(code: String): Boolean = mutableState.value.let { code in it.downloading || code in it.removing }

    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
}

/** The app's controller; null where no host provides one (isolated UI tests), which hides the setting. */
internal val LocalLanguageDownloads = staticCompositionLocalOf<LanguageDownloadsController?> { null }

/** The Settings → Translation row that opens the language list. */
@Composable
internal fun LanguageDownloadsSettingsRow(onOpen: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 10.dp)
                .testTag("language_downloads_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.language_downloads_title))
            Text(
                stringResource(R.string.language_downloads_row_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** Opens the language list for [controller] and refreshes what is downloaded each time it opens. */
@Composable
internal fun LanguageDownloadsDialog(
    controller: LanguageDownloadsController,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsState()
    LanguageDownloadsScreen(
        state = state,
        onDownload = controller::download,
        onRemove = controller::remove,
        onRetry = controller::refresh,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun LanguageDownloadsScreen(
    state: LanguageDownloadsState,
    onDownload: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = currentInterfaceLocale()
    val collator = remember(locale) { Collator.getInstance(locale) }
    val groups = languageDownloadGroups(state, { languageDisplayName(it, locale) }, collator)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_language_downloads")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.language_downloads_back))
                    }
                    Text(
                        stringResource(R.string.language_downloads_title),
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("language_downloads_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.language_downloads_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    when {
                        state.loadFailed && state.available.isEmpty() -> item { LoadFailed(onRetry) }
                        state.loading && state.available.isEmpty() -> item { Loading() }
                        else -> {
                            item { GroupHeader(R.string.language_downloads_group_on_device) }
                            items(groups.onDevice, key = { "on_device_${it.code}" }) { row ->
                                LanguageRow(row, onDownload, onRemove)
                            }
                            if (groups.available.isNotEmpty()) {
                                item { GroupHeader(R.string.language_downloads_group_available) }
                                items(groups.available, key = { "available_${it.code}" }) { row ->
                                    LanguageRow(row, onDownload, onRemove)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    @StringRes title: Int,
) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LanguageRow(
    row: LanguageDownloadRow,
    onDownload: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("language_row_${row.code}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(row.status.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (row.status == LanguageDownloadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (row.code == JAPANESE_LANGUAGE) {
                    Text(
                        stringResource(R.string.language_downloads_japanese_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                row.status == LanguageDownloadStatus.BUILT_IN -> Unit
                row.status == LanguageDownloadStatus.DOWNLOADING || row.status == LanguageDownloadStatus.REMOVING ->
                    CircularProgressIndicator(Modifier.size(24.dp).testTag("language_busy_${row.code}"), strokeWidth = 2.dp)
                row.onDevice ->
                    TextButton(onClick = { onRemove(row.code) }, modifier = Modifier.testTag("remove_language_${row.code}")) {
                        Text(stringResource(R.string.language_downloads_remove))
                    }
                else ->
                    FilledTonalButton(onClick = { onDownload(row.code) }, modifier = Modifier.testTag("download_language_${row.code}")) {
                        Text(stringResource(R.string.language_downloads_download))
                    }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Loading() {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.language_downloads_loading), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.language_downloads_load_failed), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag("retry_language_downloads")) {
            Text(stringResource(R.string.language_downloads_retry))
        }
    }
}
