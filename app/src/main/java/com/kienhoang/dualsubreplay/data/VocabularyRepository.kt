package com.kienhoang.dualsubreplay.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Separate from preferences: resetting appearance/settings never erases learning history. */
internal class VocabularyRepository internal constructor(context: Context, databaseName: String = "vocabulary.db") {
    private val database = object : SQLiteOpenHelper(context, databaseName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE words (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    val clipDirectory = File(context.noBackupFilesDir, if (databaseName == "vocabulary.db") "word-clips" else "clips-$databaseName")
    private val mutex = Mutex()
    private val _words = MutableStateFlow<List<SavedWord>>(emptyList())
    val words = _words.asStateFlow()
    internal fun close() = database.close()

    fun clipFile(word: SavedWord): File = File(clipDirectory, "${word.id}-${word.clipGeneration}.mp4")

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock { publish() } }

    /** Recover after process death/cancellation, without touching files owned by active jobs. */
    suspend fun reconcileDownloads(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val activeWords = androidx.work.WorkManager.getInstance(context).getWorkInfosByTag("word-clip").get()
            .filter { !it.state.isFinished }.flatMap { it.tags }
            .filter { it.startsWith("word:") }.map { it.removePrefix("word:") }.toSet()
        val wordsById = readAll().associateBy { it.id }
        clipDirectory.listFiles()?.forEach { file ->
            val owner = wordsById[file.name.substringBefore('-')]
            if (owner?.id !in activeWords && (owner == null || owner.clipStatus != "ready" || file != clipFile(owner))) {
                file.deleteRecursively()
            }
        }
        wordsById.values.filter { it.id !in activeWords && it.clipStatus in listOf("queued", "downloading") }.forEach {
            write(it.copy(clipStatus = "failed", clipError = "Download interrupted. Tap Retry to start again."))
        }
        publish()
    } }

    private fun readAll(): List<SavedWord> = database.readableDatabase.query(
        "words", arrayOf("payload"), null, null, null, null, "rowid DESC",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(decodeWord(JSONObject(cursor.getString(0))))
    } }

    private fun publish() { _words.value = readAll() }
    private fun write(word: SavedWord) {
        database.writableDatabase.insertWithOnConflict("words", null, ContentValues().apply {
            put("id", word.id)
            put("payload", encodeWord(word).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not save word" } }
    }

    suspend fun save(word: SavedWord): SavedWord = withContext(Dispatchers.IO) { mutex.withLock {
        val old = readAll().firstOrNull { it.id == word.id }
        val updated = if (old == null) word else word.copy(
            dueAt = old.dueAt, intervalMs = old.intervalMs, clipGeneration = old.clipGeneration,
            clipStatus = old.clipStatus, clipError = old.clipError,
        )
        write(updated)
        publish()
        updated
    } }

    suspend fun update(id: String, change: (SavedWord) -> SavedWord): SavedWord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = readAll().firstOrNull { it.id == id } ?: return@withLock null
            val updated = change(old)
            write(updated)
            publish()
            updated
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        database.writableDatabase.delete("words", "id = ?", arrayOf(id))
        // Filenames are generated SHA-256 IDs, never user-provided paths.
        clipDirectory.listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.delete() }
        publish()
    } }

    companion object {
        @Volatile private var instance: VocabularyRepository? = null
        fun get(context: Context): VocabularyRepository = instance ?: synchronized(this) {
            instance ?: VocabularyRepository(context.applicationContext).also { instance = it }
        }
    }
}

internal fun encodeWord(w: SavedWord): JSONObject = JSONObject().apply {
    put("id", w.id); put("word", w.word); put("reading", w.reading)
    put("wordLanguage", w.wordLanguage); put("meaningLanguage", w.meaningLanguage)
    put("meaning", w.meaning); put("sentence", w.sentence); put("translatedSentence", w.translatedSentence)
    put("videoId", w.videoId); put("startMs", w.startMs); put("endMs", w.endMs)
    put("translated", w.translated); put("online", w.online); put("offline", w.offline)
    put("clipStatus", w.clipStatus); put("clipError", w.clipError); put("clipGeneration", w.clipGeneration)
    put("dueAt", w.dueAt); put("intervalMs", w.intervalMs)
}

internal fun decodeWord(j: JSONObject): SavedWord = SavedWord(
    id = j.getString("id"), word = j.getString("word"), reading = j.optString("reading").takeIf { it.isNotBlank() },
    wordLanguage = j.getString("wordLanguage"), meaningLanguage = j.getString("meaningLanguage"),
    meaning = j.getString("meaning"), sentence = j.getString("sentence"),
    translatedSentence = j.optString("translatedSentence").takeIf { it.isNotBlank() },
    videoId = j.optString("videoId").takeIf { it.isNotBlank() }, startMs = j.getLong("startMs"), endMs = j.getLong("endMs"),
    translated = j.getBoolean("translated"), online = j.getBoolean("online"), offline = j.getBoolean("offline"),
    clipStatus = j.optString("clipStatus", "none"), clipError = j.optString("clipError").takeIf { it.isNotBlank() },
    clipGeneration = j.optLong("clipGeneration"), dueAt = j.optLong("dueAt"), intervalMs = j.optLong("intervalMs"),
)
