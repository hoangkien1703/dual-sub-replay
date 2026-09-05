package com.kienhoang.dualsubreplay.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class ClipRequest(val videoId: String, val startMs: Long, val endMs: Long, val processId: String)
internal interface ClipDownloadBackend {
    suspend fun download(request: ClipRequest, directory: File, progress: (Int) -> Unit): File
    fun cancel(processId: String)
}

internal fun clipDownloadArguments(request: ClipRequest, output: File): List<Pair<String, String?>> {
    require(validClipRange(request.videoId, request.startMs, request.endMs))
    return listOf(
        "--no-playlist" to null, "--no-cache-dir" to null,
        "--socket-timeout" to "20", "--retries" to "2", "--fragment-retries" to "2",
        "--download-sections" to String.format(Locale.ROOT, "*%.3f-%.3f", request.startMs / 1000.0, request.endMs / 1000.0),
        "--force-keyframes-at-cuts" to null,
        "-f" to "bv[height<=480][ext=mp4][vcodec^=avc1]+ba[ext=m4a]/b[height<=480][ext=mp4]",
        "--merge-output-format" to "mp4", "--recode-video" to "mp4",
        "-o" to output.absolutePath,
    )
}

internal class YoutubeClipBackend(private val context: Context) : ClipDownloadBackend {
    override suspend fun download(request: ClipRequest, directory: File, progress: (Int) -> Unit): File =
        runInterruptible(Dispatchers.IO) {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            val command = YoutubeDLRequest("https://www.youtube.com/watch?v=${request.videoId}")
            clipDownloadArguments(request, File(directory, "clip.%(ext)s")).forEach { (key, value) ->
                if (value == null) command.addOption(key) else command.addOption(key, value)
            }
            YoutubeDL.getInstance().execute(command, request.processId, true) { percent, _, _ ->
                progress(percent.toInt().coerceIn(0, 99))
            }
            File(directory, "clip.mp4").also { check(it.isFile && it.length() > 0) { "No playable clip was downloaded" } }
        }

    override fun cancel(processId: String) { YoutubeDL.getInstance().destroyProcessById(processId) }
}

internal const val MAX_CLIP_BYTES = 100L * 1024 * 1024
internal const val CLIP_JOB_TIMEOUT_MS = 600_000L

internal class ClipDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val wordId = inputData.getString("wordId") ?: return Result.failure()
        val generation = inputData.getLong("generation", -1)
        val repository = VocabularyRepository.get(applicationContext)
        repository.refresh()
        val word = repository.words.value.firstOrNull { it.id == wordId && it.clipGeneration == generation && it.offline }
            ?: return Result.success()
        if (word.clipStatus == "ready" && repository.clipFile(word).isFile) return Result.success()
        val backend = YoutubeClipBackend(applicationContext)
        val request = ClipRequest(word.videoId.orEmpty(), word.startMs, word.endMs, id.toString())
        val temporary = File(repository.clipDirectory, "${word.id}-$generation-${id}.part")
        try {
            setForeground(foregroundInfo())
            downloadLock.withLock {
                repository.update(wordId) { if (it.clipGeneration == generation) it.copy(clipStatus = "downloading", clipError = null) else it }
                withContext(Dispatchers.IO) { check(temporary.mkdirs() || temporary.isDirectory) }
                val file = withTimeout(CLIP_JOB_TIMEOUT_MS) {
                    coroutineScope {
                        val transfer = async { backend.download(request, temporary) { percent ->
                            setProgressAsync(Data.Builder().putInt("percent", percent).build())
                        } }
                        while (!transfer.isCompleted) {
                            delay(500)
                            val bytes = withContext(Dispatchers.IO) { temporary.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                            check(bytes <= MAX_CLIP_BYTES) { "Clip exceeds the 100 MiB storage limit" }
                        }
                        transfer.await()
                    }
                }
                withContext(Dispatchers.IO) {
                    check(file.length() <= MAX_CLIP_BYTES) { "Clip exceeds the 100 MiB storage limit" }
                    val media = MediaMetadataRetriever()
                    try {
                        media.setDataSource(file.absolutePath)
                        val duration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        check(duration > 0 && kotlin.math.abs(duration - (word.endMs - word.startMs)) <= 2000) { "Downloaded clip has an unexpected duration" }
                        check(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" &&
                            media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "Clip must contain video and audio" }
                    } finally { media.release() }
                }
                repository.update(wordId) { current ->
                    if (current.clipGeneration != generation || !current.offline || current.clipStatus != "downloading") current
                    else {
                        check(file.renameTo(repository.clipFile(current))) { "Could not store the downloaded clip" }
                        current.copy(clipStatus = "ready", clipError = null)
                    }
                }
            }
            return Result.success()
        } catch (error: Exception) {
            withContext(kotlinx.coroutines.NonCancellable) {
                repository.update(wordId) {
                    if (it.clipGeneration != generation) it else it.copy(clipStatus = "failed", clipError =
                        if (error is CancellationException) "Download interrupted. Tap Retry to start again."
                        else "Download unavailable. Check your connection and storage, then retry.")
                }
            }
            if (error is CancellationException) throw error
            return Result.failure()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { backend.cancel(request.processId) }
                temporary.deleteRecursively()
            }
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("word_clips", "Word clip downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "word_clips")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Saving a word clip")
            .setContentText("Downloading and trimming your example video")
            .setProgress(0, 0, true).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification)
    }

    companion object { private val downloadLock = Mutex() }
}

internal suspend fun enqueueClip(context: Context, wordId: String) {
    val repository = VocabularyRepository.get(context)
    val word = repository.update(wordId) {
        it.copy(offline = true, clipGeneration = it.clipGeneration + 1, clipStatus = "queued", clipError = null)
    } ?: return
    val work = OneTimeWorkRequestBuilder<ClipDownloadWorker>()
        .setInputData(Data.Builder().putString("wordId", word.id).putLong("generation", word.clipGeneration).build())
        .addTag("word-clip").addTag("word:${word.id}").build()
    WorkManager.getInstance(context).enqueueUniqueWork("word-clip-${word.id}", ExistingWorkPolicy.REPLACE, work)
}

internal suspend fun removeClip(context: Context, word: SavedWord) {
    val repository = VocabularyRepository.get(context)
    repository.update(word.id) { it.copy(offline = false, clipStatus = "none", clipError = null, clipGeneration = it.clipGeneration + 1) }
    WorkManager.getInstance(context).cancelUniqueWork("word-clip-${word.id}")
    withContext(Dispatchers.IO) { repository.clipFile(word).delete() }
}
