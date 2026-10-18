package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Asks the YouTube page for the transcript and runs [download] (the app's own lookup) at the same
 * time; the first success wins. When the page cannot help, the download's result or error stands,
 * so its messages and the live-subtitle fallback stay as they were; the page keeps watching after
 * that and announces a late transcript on [PageTranscriptSource.lateTranscripts].
 */
internal class PageFirstCaptionProvider(
    private val page: PageTranscriptSource,
    private val download: CaptionProvider,
    private val pageTimeoutMs: Long = PAGE_TRANSCRIPT_TIMEOUT_MS,
) : CaptionProvider {
    override suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
        learningLanguage: String?,
    ): CaptionTrackResult =
        coroutineScope {
            val fromPage = async { page.transcript(videoId, CaptionLanguageChoice(preferredLanguages, learningLanguage), pageTimeoutMs) }
            val downloaded = async { attempt { download.fetch(videoId, preferredLanguages, learningLanguage) } }
            select {
                fromPage.onAwait { track ->
                    if (track != null) {
                        downloaded.cancel()
                        track
                    } else {
                        downloaded.await().getOrThrow()
                    }
                }
                downloaded.onAwait { result ->
                    result.getOrNull()?.also { fromPage.cancel() }
                        ?: fromPage.await()
                        ?: result.getOrThrow()
                }
            }
        }

    /** The download's result or its error, without swallowing cancellation. */
    private suspend fun attempt(block: suspend () -> CaptionTrackResult): Result<CaptionTrackResult> =
        try {
            Result.success(block())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Result.failure(error)
        }
}
