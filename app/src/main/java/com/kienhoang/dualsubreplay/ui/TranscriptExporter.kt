package com.kienhoang.dualsubreplay.ui

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Where an export stands: rows done out of all rows, then whether it finished or stopped. */
internal data class TranscriptExportState(
    val done: Int,
    val total: Int,
    val result: Result = Result.RUNNING,
) {
    enum class Result { RUNNING, SAVED, FAILED, CANCELLED }
}

/**
 * Writes a [WholeTranscript] to a file the learner picked with Android's own save screen (no storage
 * permission). It streams from disk through a buffered writer, so a 6-hour transcript never sits in
 * memory, and runs in the view model's scope, so closing the sheet doesn't stop it.
 */
internal class TranscriptExporter(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
) {
    private val _state = MutableStateFlow<TranscriptExportState?>(null)
    val state: StateFlow<TranscriptExportState?> = _state.asStateFlow()
    private var job: Job? = null

    fun start(
        transcript: WholeTranscript,
        uri: Uri,
        format: TranscriptExportFormat,
        withTranslation: Boolean,
        title: String?,
    ) {
        job?.cancel()
        _state.value = TranscriptExportState(0, transcript.size)
        job =
            scope.launch {
                val saved =
                    try {
                        withContext(Dispatchers.IO) {
                            val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("No file to write to.")
                            stream.bufferedWriter(Charsets.UTF_8).use { out ->
                                transcript.export(out, format, withTranslation, title) { done, total ->
                                    _state.value = TranscriptExportState(done, total)
                                }
                            }
                        }
                    } catch (cancel: CancellationException) {
                        _state.value = _state.value?.copy(result = TranscriptExportState.Result.CANCELLED)
                        throw cancel
                    } catch (_: IOException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                _state.value =
                    (_state.value ?: TranscriptExportState(0, transcript.size)).copy(
                        result = if (saved) TranscriptExportState.Result.SAVED else TranscriptExportState.Result.FAILED,
                    )
            }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Forgets a finished export's message. */
    fun dismiss() {
        if (_state.value?.result != TranscriptExportState.Result.RUNNING) _state.value = null
    }
}
