package com.kienhoang.dualsubreplay.data

import android.app.job.JobScheduler
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
    private val mutex = Mutex()
    private val _words = MutableStateFlow<List<SavedWord>>(emptyList())
    val words = _words.asStateFlow()
    private val _warning = MutableStateFlow<String?>(null)
    val warning = _warning.asStateFlow()

    internal fun close() = database.close()

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock { publish() } }

    private fun getById(id: String): SavedWord? {
        return database.readableDatabase.query(
            "words", arrayOf("payload"), "id = ?", arrayOf(id), null, null, null,
        ).use { cursor ->
            if (cursor.moveToNext()) {
                runCatching { decodeWord(JSONObject(cursor.getString(0))) }.getOrNull()
            } else null
        }
    }

    private fun readAll(): List<SavedWord> {
        val list = mutableListOf<SavedWord>()
        var malformedCount = 0
        database.readableDatabase.query(
            "words", arrayOf("payload"), null, null, null, null, "rowid DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = cursor.getString(0)
                try {
                    list.add(decodeWord(JSONObject(payload)))
                } catch (_: Exception) {
                    malformedCount++
                }
            }
        }
        _warning.value = if (malformedCount > 0) {
            "$malformedCount malformed word records were retained in storage and skipped."
        } else null
        return list
    }

    private fun publish() {
        _words.value = readAll()
    }

    suspend fun save(word: SavedWord): SavedWord = withContext(Dispatchers.IO) { mutex.withLock {
        val old = getById(word.id)
        val updated = if (old == null) word else word.copy(
            dueAt = old.dueAt,
            intervalMs = old.intervalMs,
        )
        if (old == null) {
            val rowId = database.writableDatabase.insert("words", null, ContentValues().apply {
                put("id", updated.id)
                put("payload", encodeWord(updated).toString())
            })
            check(rowId != -1L) { "Could not save word" }
        } else {
            val rows = database.writableDatabase.update("words", ContentValues().apply {
                put("payload", encodeWord(updated).toString())
            }, "id = ?", arrayOf(updated.id))
            check(rows > 0) { "Could not update word" }
        }
        publish()
        updated
    } }

    suspend fun update(id: String, change: (SavedWord) -> SavedWord): SavedWord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = getById(id) ?: return@withLock null
            val updated = change(old)
            val rows = database.writableDatabase.update("words", ContentValues().apply {
                put("payload", encodeWord(updated).toString())
            }, "id = ?", arrayOf(id))
            check(rows > 0) { "Could not update word" }
            publish()
            updated
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        database.writableDatabase.delete("words", "id = ?", arrayOf(id))
        publish()
    } }

    companion object {
        @Volatile private var instance: VocabularyRepository? = null
        fun get(context: Context): VocabularyRepository = instance ?: synchronized(this) {
            instance ?: VocabularyRepository(context.applicationContext).also { instance = it }
        }
    }
}

internal fun retireLegacyDownloadJobs(context: Context) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    try {
        for (job in scheduler.allPendingJobs) {
            val serviceName = job.service.className
            if (serviceName.contains("androidx.work") || serviceName.contains("SystemJobService")) {
                scheduler.cancel(job.id)
            }
        }
    } catch (_: Throwable) {
        // Framework JobScheduler query failures should never interrupt normal startup.
    }
}

internal fun encodeWord(w: SavedWord): JSONObject = JSONObject().apply {
    put("id", w.id)
    put("word", w.word)
    put("reading", w.reading)
    put("wordLanguage", w.wordLanguage)
    put("meaningLanguage", w.meaningLanguage)
    put("meaning", w.meaning)
    put("sentence", w.sentence)
    put("translatedSentence", w.translatedSentence)
    put("videoId", w.videoId)
    put("startMs", w.startMs)
    put("endMs", w.endMs)
    put("translated", w.translated)
    put("online", w.online)
    put("dueAt", w.dueAt)
    put("intervalMs", w.intervalMs)
}

internal fun decodeWord(j: JSONObject): SavedWord = SavedWord(
    id = j.getString("id"),
    word = j.getString("word"),
    reading = j.optString("reading").takeIf { it.isNotBlank() },
    wordLanguage = j.getString("wordLanguage"),
    meaningLanguage = j.getString("meaningLanguage"),
    meaning = j.getString("meaning"),
    sentence = j.getString("sentence"),
    translatedSentence = j.optString("translatedSentence").takeIf { it.isNotBlank() },
    videoId = j.optString("videoId").takeIf { it.isNotBlank() },
    startMs = j.getLong("startMs"),
    endMs = j.getLong("endMs"),
    translated = j.getBoolean("translated"),
    online = j.optBoolean("online", false),
    dueAt = j.optLong("dueAt"),
    intervalMs = j.optLong("intervalMs"),
)
