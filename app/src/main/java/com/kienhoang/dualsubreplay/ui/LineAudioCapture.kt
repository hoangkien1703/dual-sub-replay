package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kienhoang.dualsubreplay.data.AnkiAudioFiles
import com.kienhoang.dualsubreplay.data.AnkiCardAudio
import com.kienhoang.dualsubreplay.data.AnkiConnectNotes
import com.kienhoang.dualsubreplay.data.AnkiDroidNotes
import com.kienhoang.dualsubreplay.data.AudioLine
import com.kienhoang.dualsubreplay.data.LineAudioStore
import com.kienhoang.dualsubreplay.data.PENDING_ANKIDROID
import com.kienhoang.dualsubreplay.data.PendingCardAudio
import com.kienhoang.dualsubreplay.data.PendingCardAudioStore
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.audioLineOf
import com.kienhoang.dualsubreplay.data.lineAudioReady
import com.kienhoang.dualsubreplay.data.parseLinePcm
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** How often the page recorder is kept on and finished lines are cut. */
private const val LINE_AUDIO_CHECK_MS = 1_500L

/** A line the page couldn't cut is asked again after this long (it may be replayed meanwhile). */
private const val LINE_AUDIO_RETRY_MS = 5_000L

/** A card whose clip AnkiDroid or AnkiConnect didn't take is tried again after this long. */
private const val PENDING_AUDIO_RETRY_MS = 60_000L

private const val LINE_AUDIO_PRUNE_MS = 60 * 60 * 1000L

/**
 * Connects the page recorder (which needs the one WebView) to Send to (which needs a clip now):
 * while the recorder runs, [captureNow] cuts a line the page still holds.
 */
internal object LineAudioBridge {
    @Volatile
    private var capturer: (suspend (AudioLine) -> File?)? = null

    /** TEMPORARY (preview only): the page recorder's state and the last clip result, shown in Settings. */
    val status = MutableStateFlow("")

    @Volatile
    var lastClip = ""

    /** Wakes the recorder's loop early, for example when a card starts waiting for its clip. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    fun bind(capture: suspend (AudioLine) -> File?): Any {
        capturer = capture
        return capture
    }

    fun unbind(token: Any) {
        if (capturer === token) capturer = null
    }

    suspend fun captureNow(line: AudioLine): File? = capturer?.invoke(line)
}

/**
 * The sounds Send to puts on a card (Settings → Study tools → Add audio to cards): the word in the
 * word card's voice, and the line's clip when the page recorded it. A card sent before then waits
 * in [PendingCardAudioStore] for the recorder to add it.
 */
internal class CardAudioSource(
    context: Context,
) : AnkiCardAudio {
    private val application = context.applicationContext
    private val words = WordAudioRecorder(application)
    private val clips = LineAudioStore.of(application)
    private val pending = PendingCardAudioStore(application)

    override suspend fun files(word: SavedWord): AnkiAudioFiles =
        try {
            val line = audioLineOf(word)
            val sentence = line?.let { withContext(Dispatchers.IO) { clips.file(it) } ?: LineAudioBridge.captureNow(it) }
            AnkiAudioFiles(word = words.record(word), sentence = sentence)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            AnkiAudioFiles()
        }

    override fun awaitSentence(item: PendingCardAudio) {
        pending.add(item)
        LineAudioBridge.wake.trySend(Unit)
    }
}

/** Adds recorded clips to the cards that wait for them. */
private class PendingCardAudioDelivery(
    context: Context,
    private val clips: LineAudioStore,
    private val pending: PendingCardAudioStore,
) {
    private val anki = AnkiDroidNotes(context)
    private val ankiConnect = AnkiConnectNotes.create(context)
    private val lastTry = HashMap<PendingCardAudio, Long>()

    suspend fun deliver() {
        val now = SystemClock.elapsedRealtime()
        for (item in withContext(Dispatchers.IO) { pending.all() }) {
            val clip = withContext(Dispatchers.IO) { clips.file(item.line) } ?: continue
            if (lastTry[item]?.let { now - it < PENDING_AUDIO_RETRY_MS } == true) continue
            lastTry[item] = now
            val done =
                if (item.app == PENDING_ANKIDROID) anki.addSentenceAudio(item, clip) else ankiConnect.addSentenceAudio(item, clip)
            if (done) withContext(Dispatchers.IO) { pending.remove(item) }
        }
    }
}

/**
 * The line recorder of the one YouTube page (owner pick 2026-11-09, spec
 * docs/specs/2026-11-09-anki-word-and-sentence-audio.md). While Add audio to cards is on and Send
 * to can be used (AnkiDroid installed, or AnkiConnect picked), it keeps [webLineAudioScript] on,
 * cuts the clip of every saved word's line and every waiting card's line once that line has played,
 * and adds the clips to waiting cards. Otherwise the page recorder is stopped.
 */
@Composable
internal fun LineAudioPageScripts(
    webView: WebView,
    active: Boolean,
) {
    val context = LocalContext.current
    val settings by rememberStudySettings()
    val ankiDroidInstalled = remember(context, settings.ankiDroid) { settings.ankiDroid && AnkiDroidNotes(context).installed() }
    val enabled = lineAudioWanted(settings, ankiDroidInstalled)
    LaunchedEffect(webView, enabled, active) {
        if (!active) return@LaunchedEffect
        if (!enabled) {
            if (isYouTubeWebUrl(webView.url.orEmpty())) webView.evaluateJavascript(webLineAudioScript(on = false, videoId = null), null)
            return@LaunchedEffect
        }
        val clips = LineAudioStore.of(context)
        val pending = PendingCardAudioStore(context)
        val delivery = PendingCardAudioDelivery(context, clips, pending)
        val vocabulary = VocabularyRepository.get(context)
        val tried = HashMap<AudioLine, Long>()
        var lastPrune = 0L
        val capture: suspend (AudioLine) -> File? = { line -> captureLine(webView, clips, line) }
        val token = LineAudioBridge.bind(capture)
        try {
            while (isActive) {
                val url = webView.url.orEmpty()
                val videoId = browseVideoSelection(url)?.videoId?.takeIf { isYouTubeWebUrl(url) }
                if (videoId != null) webView.evaluateJavascript(webLineAudioScript(on = true, videoId = videoId), null)
                val playback = WebVideoBridge.playback.value?.takeIf { it.videoId == videoId }
                val debug = runCatching { org.json.JSONTokener(evaluate(webView, webLineAudioDebugScript()) ?: "").nextValue() }.getOrNull()
                LineAudioBridge.status.value =
                    "video=$videoId playback=${WebVideoBridge.playback.value?.let {
                        "${it.videoId}@${it.positionMs}"
                    }}\n$debug\n${LineAudioBridge.lastClip}"
                val waiting = withContext(Dispatchers.IO) { pending.all() }
                if (playback != null) {
                    val now = SystemClock.elapsedRealtime()
                    val lines =
                        (vocabulary.words.value.mapNotNull(::audioLineOf) + waiting.map { it.line })
                            .filter { it.videoId == videoId && lineAudioReady(it, playback.positionMs, playback.paused) }
                            .distinct()
                    for (line in lines) {
                        if (tried[line]?.let { now - it < LINE_AUDIO_RETRY_MS } == true) continue
                        if (withContext(Dispatchers.IO) { clips.has(line) }) continue
                        tried[line] = now
                        capture(line)
                    }
                }
                if (waiting.isNotEmpty()) delivery.deliver()
                val now = SystemClock.elapsedRealtime()
                // Before the saved words have loaded, every clip would look unused.
                if (now - lastPrune > LINE_AUDIO_PRUNE_MS && vocabulary.words.value.isNotEmpty()) {
                    lastPrune = now
                    val needed = (vocabulary.words.value.mapNotNull(::audioLineOf) + waiting.map { it.line }).toSet()
                    withContext(Dispatchers.IO) { clips.prune(needed, System.currentTimeMillis()) }
                }
                withTimeoutOrNull(LINE_AUDIO_CHECK_MS) { LineAudioBridge.wake.receive() }
            }
        } finally {
            LineAudioBridge.unbind(token)
        }
    }
}

/** The recorder runs only where its clips can go somewhere. */
internal fun lineAudioWanted(
    settings: StudySettings,
    ankiDroidInstalled: Boolean,
): Boolean = settings.ankiDroid && settings.cardAudio && (ankiDroidInstalled || settings.sendTo == FlashcardApp.ANKICONNECT)

private suspend fun evaluate(
    webView: WebView,
    script: String,
): String? =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
        }
    }

/** Cuts [line] from the page's recorded sound and keeps it as a clip; null when the page doesn't hold all of it. */
private suspend fun captureLine(
    webView: WebView,
    clips: LineAudioStore,
    line: AudioLine,
): File? {
    withContext(Dispatchers.IO) { clips.file(line) }?.let { return it }
    val raw =
        withContext(Dispatchers.Main) {
            val url = webView.url.orEmpty()
            if (!isYouTubeWebUrl(url) || browseVideoSelection(url)?.videoId != line.videoId) return@withContext null
            suspendCancellableCoroutine<String?> { continuation ->
                webView.evaluateJavascript(webLineAudioClipScript(line)) { value -> if (continuation.isActive) continuation.resume(value) }
            }
        } ?: return null
    val pcm = withContext(Dispatchers.Default) { parseLinePcm(raw) }
    LineAudioBridge.lastClip =
        "clip ${line.startMs}-${line.endMs}: " +
        if (pcm == null) "none (${raw.take(40)})" else "${pcm.samples.size / pcm.sampleRate.toFloat()} s"
    if (pcm == null) return null
    return withContext(Dispatchers.IO) { if (clips.save(line, pcm)) clips.file(line) else null }
}
