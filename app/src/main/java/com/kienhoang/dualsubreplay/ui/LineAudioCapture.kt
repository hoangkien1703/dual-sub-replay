package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kienhoang.dualsubreplay.data.AnkiCardFiles
import com.kienhoang.dualsubreplay.data.AnkiCardMedia
import com.kienhoang.dualsubreplay.data.AnkiConnectNotes
import com.kienhoang.dualsubreplay.data.AnkiDroidNotes
import com.kienhoang.dualsubreplay.data.AudioLine
import com.kienhoang.dualsubreplay.data.CardOptions
import com.kienhoang.dualsubreplay.data.CardPlace
import com.kienhoang.dualsubreplay.data.LINE_PICTURE_MAX_BYTES
import com.kienhoang.dualsubreplay.data.LineAudioStore
import com.kienhoang.dualsubreplay.data.LinePictureStore
import com.kienhoang.dualsubreplay.data.PENDING_ANKIDROID
import com.kienhoang.dualsubreplay.data.PendingCardAudio
import com.kienhoang.dualsubreplay.data.PendingCardAudioStore
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.audible
import com.kienhoang.dualsubreplay.data.audioLineOf
import com.kienhoang.dualsubreplay.data.lineAudioReady
import com.kienhoang.dualsubreplay.data.linePictureTime
import com.kienhoang.dualsubreplay.data.parseLinePcm
import com.kienhoang.dualsubreplay.data.parseLinePicture
import com.kienhoang.dualsubreplay.data.pictureLineOf
import com.kienhoang.dualsubreplay.data.videoThumbnailUrl
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
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

    @Volatile
    private var replayer: ((SavedWord) -> Boolean)? = null

    /** Lets [replayForSound] play a line in the page video; [replay] says false when it can't (another video plays). */
    fun bindReplay(replay: (SavedWord) -> Boolean): Any {
        replayer = replay
        return replay
    }

    fun unbindReplay(token: Any) {
        if (replayer === token) replayer = null
    }

    /**
     * Plays [word]'s line once, a moment before its start to just after its end, so the recorder
     * gets the sound of a card that waits for it; false when no page video can play it now.
     */
    fun replayForSound(word: SavedWord): Boolean = replayer?.invoke(word) ?: false
}

/** Like [LineAudioBridge], for stills: while the page runs, [captureNow] takes one if the video shows the line. */
internal object LinePictureBridge {
    @Volatile
    private var capturer: (suspend (AudioLine) -> File?)? = null

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
 * What Send to puts on a card (Settings → Flashcards & Anki): with Add audio to cards, the word in
 * the word card's voice and the line's clip when the page recorded it (a card sent before then
 * waits in [PendingCardAudioStore] for the recorder to add it); with Add a picture to cards, a
 * still of the line, else the video's thumbnail.
 */
internal class CardMediaSource(
    context: Context,
) : AnkiCardMedia {
    private val application = context.applicationContext
    private val words = WordAudioRecorder(application)
    private val clips = LineAudioStore.of(application)
    private val pictures = LinePictureStore.of(application)
    private val pending = PendingCardAudioStore(application)

    override suspend fun files(
        word: SavedWord,
        options: CardOptions,
    ): AnkiCardFiles =
        AnkiCardFiles(
            word = if (options.wordAudio) safely { words.record(word) } else null,
            sentence = if (options.sentenceAudio) safely { sentence(word) } else null,
            picture = if (options.pictureWanted) safely { picture(word) } else null,
        )

    private suspend fun sentence(word: SavedWord): File? {
        val line = audioLineOf(word) ?: return null
        return withContext(Dispatchers.IO) { clips.file(line) } ?: LineAudioBridge.captureNow(line)
    }

    private suspend fun picture(word: SavedWord): File? {
        val line = pictureLineOf(word) ?: return null
        return withContext(Dispatchers.IO) { pictures.file(line) }
            ?: LinePictureBridge.captureNow(line)
            ?: withContext(Dispatchers.IO) { pictures.thumbnail(line.videoId) ?: downloadThumbnail(line.videoId) }
    }

    /** The video's thumbnail from YouTube's image host, kept beside the stills; null offline. */
    private fun downloadThumbnail(videoId: String): File? {
        val url = videoThumbnailUrl(videoId) ?: return null
        val request = Request.Builder().url(url).build()
        val bytes =
            runCatching {
                thumbnailClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body.contentLength() > LINE_PICTURE_MAX_BYTES) null else body.bytes()
                }
            }.getOrNull()
        return bytes?.let { pictures.saveThumbnail(videoId, it) }
    }

    override fun awaitSentence(item: PendingCardAudio) {
        pending.add(item)
        LineAudioBridge.wake.trySend(Unit)
    }

    private companion object {
        val thumbnailClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(THUMBNAIL_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(THUMBNAIL_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        }
    }
}

private const val THUMBNAIL_TIMEOUT_S = 8L

/** One medium of a card; a failure leaves only that one out. */
private suspend fun <T> safely(make: suspend () -> T?): T? =
    try {
        make()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
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
 * and adds the clips to waiting cards. Otherwise the page recorder is stopped. While Add a picture
 * to cards is on (owner pick 2026-11-10), it also takes a still of every saved word's line while the
 * video shows it.
 */
@Composable
internal fun LineAudioPageScripts(
    webView: WebView,
    active: Boolean,
) {
    val context = LocalContext.current
    val settings by rememberStudySettings()
    val ankiDroidInstalled = remember(context, settings.ankiDroid) { settings.ankiDroid && AnkiDroidNotes(context).installed() }
    val audio = lineAudioWanted(settings, ankiDroidInstalled)
    val picture = linePictureWanted(settings, ankiDroidInstalled)
    LaunchedEffect(webView, audio, picture, active) {
        if (!active) return@LaunchedEffect
        val onYouTube = isYouTubeWebUrl(webView.url.orEmpty())
        if (!audio && onYouTube) webView.evaluateJavascript(webLineAudioScript(on = false, videoId = null), null)
        if (!audio && !picture) return@LaunchedEffect
        val page = PageCardMedia(context, webView, audio, picture)
        val token = if (audio) LineAudioBridge.bind(page::captureClip) else null
        val pictureToken = if (picture) LinePictureBridge.bind(page::capturePicture) else null
        try {
            while (isActive) {
                page.check()
                withTimeoutOrNull(LINE_AUDIO_CHECK_MS) { LineAudioBridge.wake.receive() }
            }
        } finally {
            token?.let(LineAudioBridge::unbind)
            pictureToken?.let(LinePictureBridge::unbind)
        }
    }
}

/** One check of [LineAudioPageScripts]: clips and stills of saved words' lines, waiting cards, and old files. */
private class PageCardMedia(
    context: Context,
    private val webView: WebView,
    private val audio: Boolean,
    private val picture: Boolean,
) {
    private val clips = LineAudioStore.of(context)
    private val pictures = LinePictureStore.of(context)
    private val pending = PendingCardAudioStore(context)
    private val delivery = PendingCardAudioDelivery(context, clips, pending)
    private val vocabulary = VocabularyRepository.get(context)
    private val clipTried = HashMap<AudioLine, Long>()
    private val pictureTried = HashMap<AudioLine, Long>()
    private var lastPrune = 0L

    suspend fun captureClip(line: AudioLine): File? = captureLine(webView, clips, line)

    suspend fun capturePicture(line: AudioLine): File? = captureLinePicture(webView, pictures, line)

    suspend fun check() {
        val url = webView.url.orEmpty()
        val videoId = browseVideoSelection(url)?.videoId?.takeIf { isYouTubeWebUrl(url) }
        if (audio && videoId != null) webView.evaluateJavascript(webLineAudioScript(on = true, videoId = videoId), null)
        val playback = WebVideoBridge.playback.value?.takeIf { it.videoId == videoId }
        val waiting = if (audio) withContext(Dispatchers.IO) { pending.all() } else emptyList()
        val words = vocabulary.words.value
        if (playback != null) {
            if (audio) {
                val lines =
                    (words.mapNotNull(::audioLineOf) + waiting.map { it.line })
                        .filter { it.videoId == videoId && lineAudioReady(it, playback.positionMs, playback.paused) }
                captureOnce(lines, clipTried, { clips.has(it) }, ::captureClip)
            }
            if (picture) {
                val lines = words.mapNotNull(::pictureLineOf).filter { it.videoId == videoId && linePictureTime(it, playback.positionMs) }
                captureOnce(lines, pictureTried, { pictures.file(it) != null }, ::capturePicture)
            }
        }
        if (waiting.isNotEmpty()) delivery.deliver()
        val now = SystemClock.elapsedRealtime()
        // Before the saved words have loaded, every clip would look unused.
        if (now - lastPrune > LINE_AUDIO_PRUNE_MS && words.isNotEmpty()) {
            lastPrune = now
            val wall = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                if (audio) clips.prune((words.mapNotNull(::audioLineOf) + waiting.map { it.line }).toSet(), wall)
                if (picture) pictures.prune(words.mapNotNull(::pictureLineOf).toSet(), wall)
            }
        }
    }

    /** Captures each of [lines] not kept yet, asking the page again for one only after [LINE_AUDIO_RETRY_MS]. */
    private suspend fun captureOnce(
        lines: List<AudioLine>,
        tried: HashMap<AudioLine, Long>,
        kept: (AudioLine) -> Boolean,
        capture: suspend (AudioLine) -> File?,
    ) {
        val now = SystemClock.elapsedRealtime()
        for (line in lines.distinct()) {
            if (tried[line]?.let { now - it < LINE_AUDIO_RETRY_MS } == true) continue
            if (withContext(Dispatchers.IO) { kept(line) }) continue
            tried[line] = now
            capture(line)
        }
    }
}

/** The recorder runs only where its clips can go somewhere. */
internal fun lineAudioWanted(
    settings: StudySettings,
    ankiDroidInstalled: Boolean,
): Boolean = settings.cardSentenceSound != CardPlace.OFF && sendToUsable(settings, ankiDroidInstalled)

/** Stills are taken only where they can go somewhere. */
internal fun linePictureWanted(
    settings: StudySettings,
    ankiDroidInstalled: Boolean,
): Boolean = settings.cardPicture != CardPlace.OFF && sendToUsable(settings, ankiDroidInstalled)

private fun sendToUsable(
    settings: StudySettings,
    ankiDroidInstalled: Boolean,
): Boolean = settings.ankiDroid && (ankiDroidInstalled || settings.sendTo == FlashcardApp.ANKICONNECT)

/** Takes a still of [line] from the page video and keeps it; null when the video isn't showing the line. */
private suspend fun captureLinePicture(
    webView: WebView,
    pictures: LinePictureStore,
    line: AudioLine,
): File? {
    withContext(Dispatchers.IO) { pictures.file(line) }?.let { return it }
    val raw =
        withContext(Dispatchers.Main) {
            val url = webView.url.orEmpty()
            if (!isYouTubeWebUrl(url) || browseVideoSelection(url)?.videoId != line.videoId) return@withContext null
            suspendCancellableCoroutine<String?> { continuation ->
                webView.evaluateJavascript(webLinePictureScript(line)) { value -> if (continuation.isActive) continuation.resume(value) }
            }
        } ?: return null
    val jpeg = withContext(Dispatchers.Default) { parseLinePicture(raw) } ?: return null
    return withContext(Dispatchers.IO) { pictures.save(line, jpeg) }
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
    val pcm = withContext(Dispatchers.Default) { parseLinePcm(raw)?.takeIf(::audible) } ?: return null
    return withContext(Dispatchers.IO) { if (clips.save(line, pcm)) clips.file(line) else null }
}
