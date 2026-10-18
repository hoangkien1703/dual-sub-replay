package com.kienhoang.dualsubreplay.data

import android.content.res.AssetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The English and Chinese learner data packaged with the app (`learner-data/` in the APK's assets,
 * extracted from Maven artifacts at build time). Unit tests read the same files from the classpath.
 */
internal object LearnerData {
    @Volatile
    private var assets: AssetManager? = null

    fun useAssets(manager: AssetManager) {
        assets = manager
    }

    fun open(name: String): InputStream =
        assets?.open("$DIRECTORY/$name")
            ?: LearnerData::class.java.classLoader?.getResourceAsStream("$DIRECTORY/$name")
            ?: throw FileNotFoundException(name)

    private const val DIRECTORY = "learner-data"
}

/**
 * Something built once on a background thread the first time it is needed, such as an analyzer.
 * Until it is ready [get] returns null and callers use their heuristic; [revision] changes when it
 * becomes ready, so UI can analyze again. Bundled data does not fail to load except by running
 * out of memory, so a failure is final for this process.
 */
internal class BackgroundLoaded<T : Any>(
    private val threadName: String,
    private val load: () -> T,
) {
    private val loaded = MutableStateFlow(0)
    val revision: StateFlow<Int> = loaded

    @Volatile
    private var value: T? = null

    @Volatile
    private var failed = false
    private val loading = AtomicBoolean(false)

    /** The loaded value, or null while it loads (starting the load if needed) or after it failed. */
    fun get(): T? =
        value ?: run {
            warmUp()
            null
        }

    fun warmUp() {
        if (value != null || failed || !loading.compareAndSet(false, true)) return
        thread(name = threadName, isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                value = load()
                loaded.value += 1
            } catch (_: Exception) {
                failed = true
            } catch (_: OutOfMemoryError) {
                // Low-memory devices keep the heuristic rather than crash.
                failed = true
            } finally {
                loading.set(false)
            }
        }
    }

    /** Loads and waits, for tests. Returns whether the value is ready. */
    fun awaitReady(timeoutMs: Long = 60_000): Boolean {
        warmUp()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (value == null && !failed && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return value != null
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
