package com.kienhoang.dualsubreplay.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Local per-day, per-language watched time. Rows are small: one per day and language. */
internal class ImmersionRepository internal constructor(
    context: Context,
    databaseName: String = "immersion.db",
) {
    private val database =
        object : SQLiteOpenHelper(context, databaseName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE daily_time (day TEXT NOT NULL, language TEXT NOT NULL, " +
                        "ms INTEGER NOT NULL DEFAULT 0, videos INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, language))",
                )
            }

            override fun onUpgrade(
                db: SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }
    private val mutex = Mutex()

    // Writes outlive the ViewModel so the last batch is kept when the screen closes.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _days = MutableStateFlow<List<ImmersionDay>>(emptyList())
    val days = _days.asStateFlow()

    internal fun close() = database.close()

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock { publish() } }

    /** Fire-and-forget: tracking must never block or crash playback. */
    fun record(deltas: List<ImmersionDelta>) {
        if (deltas.isEmpty()) return
        writeScope.launch { runCatching { add(deltas) } }
    }

    suspend fun add(deltas: List<ImmersionDelta>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    deltas.filter { it.ms > 0 || it.videos > 0 }.forEach { delta ->
                        val day = delta.day.toString()
                        // SQLite UPSERT needs API 30; insert-or-ignore then add keeps minSdk 26 working.
                        db.insertWithOnConflict(
                            "daily_time",
                            null,
                            ContentValues().apply {
                                put("day", day)
                                put("language", delta.language)
                                put("ms", 0L)
                                put("videos", 0)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        db.execSQL(
                            "UPDATE daily_time SET ms = ms + ?, videos = videos + ? WHERE day = ? AND language = ?",
                            arrayOf<Any>(delta.ms.coerceAtLeast(0), delta.videos.coerceAtLeast(0), day, delta.language),
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                publish()
            }
        }

    /** Every stored day, read now (Backup & sharing). */
    suspend fun allDays(): List<ImmersionDay> = withContext(Dispatchers.IO) { mutex.withLock { readAll() } }

    /**
     * Restores [days] from a backup: each day and language keeps the higher time and video count of
     * the two, so restoring the same backup twice never adds the time again.
     */
    suspend fun mergeDays(days: List<ImmersionDay>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    days.forEach { row ->
                        val day = row.day.toString()
                        db.insertWithOnConflict(
                            "daily_time",
                            null,
                            ContentValues().apply {
                                put("day", day)
                                put("language", row.language)
                                put("ms", 0L)
                                put("videos", 0)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        db.execSQL(
                            "UPDATE daily_time SET ms = MAX(ms, ?), videos = MAX(videos, ?) WHERE day = ? AND language = ?",
                            arrayOf<Any>(row.ms.coerceAtLeast(0), row.videos.coerceAtLeast(0), day, row.language),
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                publish()
            }
        }

    private fun readAll(): List<ImmersionDay> {
        val list = mutableListOf<ImmersionDay>()
        database.readableDatabase
            .query("daily_time", arrayOf("day", "language", "ms", "videos"), null, null, null, null, "day")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val day = runCatching { LocalDate.parse(cursor.getString(0)) }.getOrNull() ?: continue
                    list.add(ImmersionDay(day, cursor.getString(1), cursor.getLong(2), cursor.getInt(3)))
                }
            }
        return list
    }

    private fun publish() {
        _days.value = readAll()
    }

    companion object {
        @Volatile private var instance: ImmersionRepository? = null

        fun get(context: Context): ImmersionRepository =
            instance ?: synchronized(this) {
                instance ?: ImmersionRepository(context.applicationContext).also { instance = it }
            }
    }
}
