package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException

/** The `trackId` of subtitles Gemini wrote, so the label can say "(AI subtitles)". */
internal const val AI_SUBTITLE_TRACK_ID = "ai"

/**
 * How much of the video one request covers. Flash-Lite answered a 3-minute part in 6–9 s in the
 * 2026-11-08 test, so a 5-minute part shows its first lines quickly and long videos stay well under
 * the free per-minute token limit.
 */
internal const val AI_SUBTITLE_PART_MS = 5 * 60_000L

/** A line may start this much before its part, or a part-relative time is taken for one. */
private const val PART_EDGE_SLACK_MS = 2_000L

/** A line with no sensible end lasts this long, or until the next line. */
private const val DEFAULT_LINE_MS = 2_000L

/** Two lines overlapping by more than this are the same line, written at the end of one part and the start of the next. */
private const val DUPLICATE_OVERLAP_MS = 1_000L

/** Unknown lengths stop looking forward after this, even if Gemini never says the video ended. */
private const val MAX_UNKNOWN_LENGTH_MS = 12 * 60 * 60_000L

/** What Gemini wrote for one part of a video: the spoken language and the lines, with times in the whole video. */
internal data class AiSubtitlePart(
    val language: String,
    val cues: List<RawCaptionCue>,
)

/** Why Gemini's reply could not be used. */
internal class AiSubtitleReplyException(
    message: String,
    /** Gemini declined to write it (for example a song's lyrics), as opposed to a broken reply. */
    val refused: Boolean = false,
) : IOException(message)

/** The Gemini `generateContent` body for one part: the video's address, the part, and the reply's shape. */
internal fun aiSubtitleRequestBody(
    videoId: String,
    startMs: Long,
    endMs: Long,
): String {
    val video =
        JSONObject()
            .put("file_data", JSONObject().put("file_uri", "https://www.youtube.com/watch?v=$videoId"))
            .put(
                "video_metadata",
                JSONObject()
                    .put("start_offset", "${startMs / 1000}s")
                    .put("end_offset", "${endMs / 1000}s")
                    // Only the sound matters; few frames keep the request small (about 33 tokens a second).
                    .put("fps", 0.2),
            )
    val line =
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("start", JSONObject().put("type", "STRING"))
                    .put("end", JSONObject().put("type", "STRING"))
                    .put("text", JSONObject().put("type", "STRING")),
            ).put("required", JSONArray(listOf("start", "end", "text")))
    val schema =
        JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("language", JSONObject().put("type", "STRING"))
                    .put("lines", JSONObject().put("type", "ARRAY").put("items", line)),
            ).put("required", JSONArray(listOf("language", "lines")))
    return JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(video).put(JSONObject().put("text", AI_SUBTITLE_PROMPT))),
            ),
        ).put(
            "generationConfig",
            JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("mediaResolution", "MEDIA_RESOLUTION_LOW"),
        ).toString()
}

/**
 * Times are asked as text: asked for seconds as numbers, gemini-2.5-flash wrote minutes as decimals
 * (2.484 for 2:48.4) in the 2026-11-08 test.
 */
private const val AI_SUBTITLE_PROMPT =
    "Write subtitles for everything spoken in this video, in the language that is spoken. Do not translate. " +
        "Each line has at most about 12 words and its start and end time as MM:SS.mmm (minutes:seconds.milliseconds) " +
        "counted from the start of the whole video. Leave out music, sound descriptions and silence. " +
        "language is the spoken language as a BCP-47 code such as es, ja or zh-Hans."

/**
 * Reads Gemini's reply for the part from [partStartMs] to [partEndMs]. Times the model counted from
 * the start of the part are moved into the video; empty lines and lines outside the part are left out.
 */
internal fun parseAiSubtitleReply(
    body: String,
    partStartMs: Long,
    partEndMs: Long,
): AiSubtitlePart {
    val root =
        try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw AiSubtitleReplyException("The reply was not JSON: ${error.message}")
        }
    root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf(String::isNotBlank)?.let {
        throw AiSubtitleReplyException("Gemini blocked the request: $it", refused = true)
    }
    val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiSubtitleReplyException("The reply had no answer.")
    val text = replyText(candidate)
    if (text.isBlank()) {
        val reason = candidate.optString("finishReason")
        throw AiSubtitleReplyException("The reply had no text ($reason).", refused = reason in REFUSAL_REASONS)
    }
    val answer =
        try {
            JSONTokener(stripCodeFence(text)).nextValue()
        } catch (error: JSONException) {
            throw AiSubtitleReplyException("The subtitles were not JSON: ${error.message}")
        }
    val language = (answer as? JSONObject)?.optString("language")?.trim().orEmpty()
    val lines =
        (answer as? JSONObject)?.optJSONArray("lines") ?: answer as? JSONArray ?: throw AiSubtitleReplyException("The reply had no lines.")
    return AiSubtitlePart(language, aiSubtitleCues(lines, partStartMs, partEndMs))
}

private val REFUSAL_REASONS = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "OTHER")

/** The answer's text, without thinking parts. */
private fun replyText(candidate: JSONObject): String {
    val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
    return buildString {
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (!part.optBoolean("thought")) append(part.optString("text"))
        }
    }
}

private fun stripCodeFence(text: String): String =
    text
        .trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

/** The lines in [lines] as cues in the video, in time order. */
internal fun aiSubtitleCues(
    lines: JSONArray,
    partStartMs: Long,
    partEndMs: Long,
): List<RawCaptionCue> {
    val raw =
        (0 until lines.length()).mapNotNull { index ->
            val line = lines.optJSONObject(index) ?: return@mapNotNull null
            val start = parseAiSubtitleTime(line.opt("start")) ?: return@mapNotNull null
            val text = line.optString("text").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return@mapNotNull null
            Triple(start, parseAiSubtitleTime(line.opt("end")), text)
        }
    if (raw.isEmpty()) return emptyList()
    // A model asked for a part sometimes counts from the part's start (seen 2026-11-08 with Flash-Lite).
    val shift = if (partStartMs > 0 && raw.minOf { it.first } < partStartMs - PART_EDGE_SLACK_MS) partStartMs else 0L
    val inPart =
        raw
            .map { (start, end, text) -> Triple(start + shift, end?.plus(shift), text) }
            .filter { it.first in (partStartMs - PART_EDGE_SLACK_MS)..(partEndMs + PART_EDGE_SLACK_MS) }
            .sortedBy { it.first }
    return inPart.mapIndexed { index, (start, end, text) ->
        val next = inPart.getOrNull(index + 1)?.first
        val fallbackEnd = next?.takeIf { it > start }?.coerceAtMost(start + DEFAULT_LINE_MS) ?: (start + DEFAULT_LINE_MS)
        RawCaptionCue(start.coerceAtLeast(0), end?.takeIf { it > start } ?: fallbackEnd, text)
    }
}

private val TIME_PATTERN = Regex("""^(\d{1,3}(?::\d{1,3}){0,2})(?:[.,](\d{1,6}))?$""")

/**
 * A time Gemini wrote, in milliseconds: `MM:SS.mmm`, `HH:MM:SS.mmm`, `MM:SS:mmm` (seen with
 * gemini-2.5-flash), or seconds as a number or text. Null when it is none of these.
 */
internal fun parseAiSubtitleTime(raw: Any?): Long? {
    if (raw is Number) return (raw.toDouble() * 1000).takeIf { it.isFinite() && it >= 0 }?.toLong()
    val text = (raw as? String)?.trim() ?: return null
    val match = TIME_PATTERN.matchEntire(text) ?: return null
    var groups = match.groupValues[1].split(':')
    var fraction = match.groupValues[2]
    if (groups.size == 3 && fraction.isEmpty() && groups[2].length == 3) {
        fraction = groups[2]
        groups = groups.take(2)
    }
    if (groups.drop(1).any { it.toInt() >= 60 }) return null
    val seconds = groups.fold(0L) { total, part -> total * 60 + part.toLong() }
    val millis = if (fraction.isEmpty()) 0L else (fraction + "00").take(3).toLong()
    return seconds * 1000 + millis
}

/** The part of the video [positionMs] falls in: requests start there, so the playing part comes first. */
internal fun aiSubtitlePartStart(positionMs: Long): Long = (positionMs.coerceAtLeast(0) / AI_SUBTITLE_PART_MS) * AI_SUBTITLE_PART_MS

/**
 * The next part to ask for: from [firstStartMs] forward to [endMs] (unknown while null), then from
 * the beginning up to [firstStartMs]; null when every part is [done].
 */
internal fun nextAiSubtitlePart(
    firstStartMs: Long,
    done: Set<Long>,
    endMs: Long?,
): Long? {
    var start = firstStartMs
    while (start < (endMs ?: (firstStartMs + MAX_UNKNOWN_LENGTH_MS))) {
        if (start !in done) return start
        start += AI_SUBTITLE_PART_MS
    }
    start = 0L
    while (start < firstStartMs && (endMs == null || start < endMs)) {
        if (start !in done) return start
        start += AI_SUBTITLE_PART_MS
    }
    return null
}

/**
 * Subtitles Gemini wrote for one video, part by part. [endMs] is where the video ends once known,
 * and [complete] says every part is in.
 */
internal data class AiSubtitleTrack(
    val videoId: String,
    val language: String,
    val parts: Map<Long, List<RawCaptionCue>>,
    val endMs: Long? = null,
    val complete: Boolean = false,
) {
    /** Every part's lines in time order, without a line written twice where two parts meet. */
    fun cues(): List<RawCaptionCue> {
        val kept = ArrayList<RawCaptionCue>()
        for (cue in parts
            .toSortedMap()
            .values
            .flatten()
            .sortedBy { it.startMs }) {
            val previous = kept.lastOrNull()
            if (previous != null && cue.startMs < previous.endMs - DUPLICATE_OVERLAP_MS) continue
            kept += cue
        }
        return kept
    }

    fun withPart(
        startMs: Long,
        part: AiSubtitlePart,
    ): AiSubtitleTrack = copy(language = language.ifBlank { part.language }, parts = parts + (startMs to part.cues))

    fun asCaptionTrack(languageCode: String): CaptionTrackResult =
        CaptionTrackResult(
            languageCode = languageCode,
            // Not YouTube's generated captions: the page has no live captions or word timing to read for it.
            isGenerated = false,
            cues = cues(),
            trackId = AI_SUBTITLE_TRACK_ID,
        )
}

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/**
 * Made subtitles, one JSON file per video in the cache folder, so a video opened again asks Gemini
 * nothing. Keeps the [maxEntries] most recently used; disk errors only mean the video is made again.
 */
internal class AiSubtitleStore(
    private val directory: File,
    private val maxEntries: Int = 30,
) {
    @Synchronized
    fun read(videoId: String): AiSubtitleTrack? {
        if (!VIDEO_ID.matches(videoId)) return null
        val file = File(directory, "$videoId.json")
        if (!file.isFile) return null
        return try {
            decodeAiSubtitleTrack(file.readText(Charsets.UTF_8))?.takeIf { it.videoId == videoId }?.also {
                file.setLastModified(System.currentTimeMillis())
            } ?: run {
                file.delete()
                null
            }
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    fun write(track: AiSubtitleTrack) {
        if (!VIDEO_ID.matches(track.videoId)) return
        try {
            directory.mkdirs()
            val file = File(directory, "${track.videoId}.json")
            val temporary = File(directory, "${track.videoId}.json.tmp")
            temporary.writeText(encodeAiSubtitleTrack(track), Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
            prune()
        } catch (_: IOException) {
            // The subtitles still show; only reopening the video makes them again.
        }
    }

    private fun prune() {
        val files = directory.listFiles { file -> file.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(maxEntries).forEach(File::delete)
    }
}

private const val STORE_VERSION = 1

internal fun encodeAiSubtitleTrack(track: AiSubtitleTrack): String {
    val parts = JSONArray()
    track.parts.toSortedMap().forEach { (start, cues) ->
        val lines = JSONArray()
        cues.forEach { lines.put(JSONArray().put(it.startMs).put(it.endMs).put(it.text)) }
        parts.put(JSONObject().put("start", start).put("cues", lines))
    }
    return JSONObject()
        .put("version", STORE_VERSION)
        .put("videoId", track.videoId)
        .put("language", track.language)
        .put("endMs", track.endMs ?: JSONObject.NULL)
        .put("complete", track.complete)
        .put("parts", parts)
        .toString()
}

/** Null for a file of another version or a broken one. */
internal fun decodeAiSubtitleTrack(text: String): AiSubtitleTrack? =
    try {
        val root = JSONObject(text)
        if (root.getInt("version") != STORE_VERSION) {
            null
        } else {
            val parts = root.getJSONArray("parts")
            val decoded =
                (0 until parts.length()).associate { index ->
                    val part = parts.getJSONObject(index)
                    val lines = part.getJSONArray("cues")
                    part.getLong("start") to
                        (0 until lines.length()).map { line ->
                            val cue = lines.getJSONArray(line)
                            RawCaptionCue(cue.getLong(0), cue.getLong(1), cue.getString(2))
                        }
                }
            AiSubtitleTrack(
                videoId = root.getString("videoId"),
                language = root.optString("language"),
                parts = decoded,
                endMs = if (root.isNull("endMs")) null else root.getLong("endMs"),
                complete = root.optBoolean("complete"),
            )
        }
    } catch (_: JSONException) {
        null
    }
