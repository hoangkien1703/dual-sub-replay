package com.kienhoang.dualsubreplay.data

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** No live sites: exercise the actual packaged binaries and a generated audio/video fixture. */
class ClipNativeToolsTest {
    @Test(timeout = 120_000) fun bundledDownloaderAndFfmpegRunAndProducePlayableTrimmedMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        val version = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList()).addOption("--version"))
        assertTrue(version.out.trim().isNotEmpty())
        val directory = File(context.cacheDir, "native-clip-test-${UUID.randomUUID()}").apply { mkdirs() }
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        fun ffmpeg(vararg arguments: String) {
            val process = ProcessBuilder(listOf("$nativeDirectory/libffmpeg.so", "-nostdin", "-hide_banner", "-loglevel", "error", "-y") + arguments)
                .redirectErrorStream(true).apply {
                    environment()["LD_LIBRARY_PATH"] = "$packages/ffmpeg/usr/lib:$packages/python/usr/lib:$nativeDirectory"
                }.start()
            try {
                assertTrue("FFmpeg timed out", process.waitFor(30, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.exitValue())
            } finally { process.destroy() }
        }
        try {
            val source = File(directory, "source.mp4")
            val clip = File(directory, "clip.mp4")
            ffmpeg("-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", source.absolutePath)
            ffmpeg("-ss", "0.5", "-i", source.absolutePath, "-t", "1", "-c:v", "libx264", "-c:a", "aac", clip.absolutePath)
            val media = MediaMetadataRetriever()
            try {
                media.setDataSource(clip.absolutePath)
                assertEquals("yes", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals("yes", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                val duration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Unexpected duration: $duration", duration in 900..1200)
            } finally { media.release() }
        } finally { directory.deleteRecursively() }
    }
}
