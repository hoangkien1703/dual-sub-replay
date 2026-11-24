package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kienhoang.dualsubreplay.data.ProblemArea
import com.kienhoang.dualsubreplay.data.ProblemLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** YouTube's video pictures, largest first: the small ones are too blurry to read. */
private val PICTURE_NAMES = listOf("maxresdefault", "sddefault")

private const val PICTURE_MAX_BYTES = 3_000_000L
private const val PICTURE_TIMEOUT_MS = 15_000L

/** Song lists in a video picture are printed small; reading the picture at twice its size finds more of them. */
private const val PICTURE_SCALE = 2

/** The first reading may wait for Google Play services to download the text model (about a minute at most). */
private const val MODEL_WAIT_MS = 3_000L
private const val MODEL_WAITS = 20

/**
 * The lines of text in a YouTube video's picture, read on the phone by ML Kit's text recognition
 * through Google Play services (owner pick 2026-11-24): a playlist often lists its songs only
 * there. Nothing is sent anywhere but the picture's own download from YouTube. Empty when the
 * picture can't be loaded or read (offline, or a phone without Google Play services).
 */
internal class VideoPictureText(
    private val context: Context,
) {
    private val client by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(PICTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    suspend fun lines(videoId: String): List<String> {
        val picture = withContext(Dispatchers.IO) { PICTURE_NAMES.firstNotNullOfOrNull { download(videoId, it) } } ?: return emptyList()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            read(recognizer, picture)
        } catch (error: CancellationException) {
            throw error
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            ProblemLog.record(ProblemArea.CAPTIONS, "Video picture not read: ${error.javaClass.simpleName}")
            emptyList()
        } finally {
            recognizer.close()
            picture.recycle()
        }
    }

    private fun download(
        videoId: String,
        name: String,
    ): Bitmap? =
        runCatching {
            val request = Request.Builder().url("https://i.ytimg.com/vi/$videoId/$name.jpg").build()
            client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body.contentLength() > PICTURE_MAX_BYTES) return@use null
                val bytes = body.source().apply { request(PICTURE_MAX_BYTES + 1) }.buffer
                if (bytes.size > PICTURE_MAX_BYTES) return@use null
                val data = bytes.readByteArray()
                val small = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@use null
                Bitmap.createScaledBitmap(small, small.width * PICTURE_SCALE, small.height * PICTURE_SCALE, true).also {
                    if (it !== small) small.recycle()
                }
            }
        }.getOrNull()

    /** Reads [picture]; when Play services has no text model yet, asks it to download one and waits. */
    private suspend fun read(
        recognizer: TextRecognizer,
        picture: Bitmap,
    ): List<String> {
        val image = InputImage.fromBitmap(picture, 0)
        repeat(MODEL_WAITS) { attempt ->
            try {
                return recognizer
                    .process(image)
                    .awaitResult()
                    .textBlocks
                    .flatMap { block -> block.lines.map { it.text } }
            } catch (error: MlKitException) {
                if (error.errorCode != MlKitException.UNAVAILABLE) throw error
                if (attempt == 0) {
                    ModuleInstall
                        .getClient(
                            context,
                        ).installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build())
                        .awaitResult()
                }
                delay(MODEL_WAIT_MS)
            }
        }
        return emptyList()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
