package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Keeps the last few caption tracks on disk, so a video reopened after Android closed the app in
 * the background builds its transcript again without downloading its captions. Only successful
 * fetches are kept and entries expire, so a failed or old lookup still asks [delegate]. Disk errors
 * never fail a fetch. Call [fetch] off the main thread; it reads and writes files.
 */
internal class RecentCaptionTracks(
    private val delegate: CaptionProvider,
    private val directory: File,
    private val maxEntries: Int = 6,
    private val maxBytes: Long = 16L * 1024 * 1024,
    private val maxAgeMs: Long = 24L * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) : CaptionProvider {
    override suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
        learningLanguage: String?,
    ): CaptionTrackResult {
        // The learning language can change which track Auto picks, so it is part of the entry.
        val languages = preferredLanguages + listOfNotNull(learningLanguage?.let { "learning:$it" })
        val key = key(videoId, languages)
        read(key, videoId, languages)?.let { return it }
        val track = delegate.fetch(videoId, preferredLanguages, learningLanguage)
        write(key, videoId, languages, track)
        return track
    }

    @Synchronized
    private fun read(
        key: String,
        videoId: String,
        languages: List<String>,
    ): CaptionTrackResult? {
        val file = File(directory, key)
        if (!file.isFile) return null
        return try {
            val entry = JSONObject(file.readText(Charsets.UTF_8))
            val age = now() - entry.getLong("savedAt")
            val matches = entry.getString("videoId") == videoId && strings(entry.getJSONArray("languages")) == languages
            if (entry.getInt("version") != VERSION || !matches || age !in 0..maxAgeMs) {
                file.delete()
                null
            } else {
                file.setLastModified(now())
                decodeTrack(entry)
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            file.delete()
            null
        }
    }

    @Synchronized
    private fun write(
        key: String,
        videoId: String,
        languages: List<String>,
        track: CaptionTrackResult,
    ) {
        val data =
            encodeTrack(track)
                .put("version", VERSION)
                .put("videoId", videoId)
                .put("languages", JSONArray(languages))
                .put("savedAt", now())
                .toString()
                .toByteArray(Charsets.UTF_8)
        if (track.cues.isEmpty() || data.size > maxBytes || maxEntries <= 0) return
        val temporary = File(directory, "$key.tmp")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            temporary.writeBytes(data)
            val file = File(directory, key)
            file.delete()
            if (temporary.renameTo(file)) file.setLastModified(now())
            trim()
        } catch (_: IOException) {
            // Only the next reopen loses its shortcut.
        } finally {
            temporary.delete()
        }
    }

    /** Oldest-used entries go first once there are too many or they take too much space. */
    private fun trim() {
        val files =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.isFile && KEY_PATTERN.matches(it.name) }
                .sortedByDescending { it.lastModified() }
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= maxEntries || bytes > maxBytes) file.delete()
        }
    }

    private fun key(
        videoId: String,
        languages: List<String>,
    ): String {
        val input = "v$VERSION:$videoId:${languages.joinToString(",")}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // 2: Auto also weighs the title's script and the learning language; older picks are dropped.
        const val VERSION = 2
        val KEY_PATTERN = Regex("[a-f0-9]{64}")
    }
}

private fun encodeTrack(track: CaptionTrackResult): JSONObject {
    val cues = JSONArray()
    track.cues.forEach { cue ->
        val words = JSONArray()
        cue.words.forEach { word -> words.put(JSONArray().put(word.text).put(word.startMs).put(word.endMs)) }
        cues.put(
            JSONArray()
                .put(cue.startMs)
                .put(cue.endMs)
                .put(cue.text)
                .put(words),
        )
    }
    val languages = JSONArray()
    track.availableLanguages.forEach { languages.put(JSONArray().put(it.code).put(it.name)) }
    return JSONObject()
        .put("languageCode", track.languageCode)
        .put("generated", track.isGenerated)
        .put("available", languages)
        .put("cues", cues)
}

private fun decodeTrack(entry: JSONObject): CaptionTrackResult {
    val cues = entry.getJSONArray("cues")
    val languages = entry.getJSONArray("available")
    return CaptionTrackResult(
        languageCode = entry.getString("languageCode"),
        isGenerated = entry.getBoolean("generated"),
        cues =
            List(cues.length()) { index ->
                val cue = cues.getJSONArray(index)
                val words = cue.getJSONArray(3)
                RawCaptionCue(
                    startMs = cue.getLong(0),
                    endMs = cue.getLong(1),
                    text = cue.getString(2),
                    words =
                        List(words.length()) { wordIndex ->
                            val word = words.getJSONArray(wordIndex)
                            SubtitleWord(word.getString(0), word.getLong(1), word.getLong(2))
                        },
                )
            },
        availableLanguages =
            List(languages.length()) { index ->
                val language = languages.getJSONArray(index)
                CaptionLanguage(language.getString(0), language.getString(1))
            },
    )
}

private fun strings(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }
