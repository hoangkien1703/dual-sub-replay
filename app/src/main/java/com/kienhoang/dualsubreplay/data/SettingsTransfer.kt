package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * Backup & sharing (owner pick 2026-11-10, docs/specs/2026-11-11-backup-and-setup-sharing.md).
 *
 * Back up writes one file with the settings, Practice words (with known words and the learner's own
 * kanji and hanzi stories) and Progress; Restore reads it on a new phone. Share link sends only the
 * settings marked [SettingScope.SHARE] to a friend. Every value read from a file or link is checked
 * against [SETTING_SPECS] (its key and type), so a wrong or hostile file can never store a value the
 * app would crash reading, and keys this version does not know are skipped.
 */

/** Who a stored setting may travel to. */
internal enum class SettingScope {
    /** A look or study choice: goes in backups and in shared setups. */
    SHARE,

    /** Personal, tied to this person or their apps (translation language, Anki, AI): backups only. */
    BACKUP,

    /** Phone state, sign-ins, one-time notices, or a door to someone else's server: never leaves the phone. */
    NEVER,
}

internal enum class SettingType { BOOLEAN, INT, FLOAT, STRING }

internal data class SettingSpec(
    val scope: SettingScope,
    val type: SettingType,
)

private fun share(type: SettingType) = SettingSpec(SettingScope.SHARE, type)

private fun backup(type: SettingType) = SettingSpec(SettingScope.BACKUP, type)

private fun never(type: SettingType) = SettingSpec(SettingScope.NEVER, type)

private val B = SettingType.BOOLEAN
private val I = SettingType.INT
private val F = SettingType.FLOAT
private val S = SettingType.STRING

/** The preference files a backup reads; anything else (keys, sign-ins, caches, notices) is never read. */
internal const val APP_PREFERENCES_FILE = "dual_sub_preferences"
internal const val AI_PREFERENCES_FILE = "ai_assistant"
internal const val DECK_PREFERENCES_FILE = "practice_decks"

/**
 * Every key stored in [APP_PREFERENCES_FILE] and [AI_PREFERENCES_FILE], and where it may go. A new
 * setting must be added here: `SettingsTransferTest` fails for any `*_PREFERENCE` constant or
 * resettable setting missing from this list, so a new setting is never silently left out of backups.
 */
internal val SETTING_SPECS: Map<String, SettingSpec> =
    mapOf(
        // Languages: what you learn travels; what you read it in is yours.
        "preferred_caption_language" to share(S),
        "learning_language" to share(S),
        "third_subtitle_language" to share(S),
        "third_subtitle_line_enabled" to share(B),
        "target_language" to backup(S),
        "recent_target_languages" to backup(S),
        // Reading, layout and captions.
        "font_scale" to share(F),
        "portrait_panel_fit_offset_fraction" to share(F),
        "landscape_split_enabled" to share(B),
        "landscape_video_fraction" to share(F),
        "player_experience_mode" to share(S),
        "caption_format" to share(S),
        "translation_position" to share(S),
        "transcript_follow" to share(S),
        "original_caption_visibility" to share(S),
        "translated_caption_visibility" to share(S),
        "highlight_spoken_words_enabled" to share(B),
        "split_long_sentences" to share(B),
        "translation_engine" to share(S),
        "google_wait" to share(S),
        "preload_translation_models" to share(B),
        "enhanced_natural_subtitles" to share(B),
        "hide_sound_cues" to share(B),
        // Overlay and fullscreen; dragged positions fit this screen only.
        "auto_overlay_fullscreen" to share(B),
        "auto_overlay_landscape" to share(B),
        "auto_avoid_player_controls" to share(B),
        "remember_overlay_position" to share(B),
        "movable_subtitle_box" to share(B),
        "lock_overlay_to_video_player" to share(B),
        "overlay_vertical_position" to backup(F),
        "overlay_side_position" to backup(F),
        "collapsed_cc_horizontal_position" to backup(F),
        "collapsed_cc_vertical_position" to backup(F),
        // Colors and theme.
        "custom_subtitle_colors_enabled" to share(B),
        "subtitle_original_color" to share(S),
        "subtitle_translated_color" to share(S),
        "subtitle_highlight_color" to share(S),
        "subtitle_box_background" to share(S),
        "app_color_theme" to share(S),
        "app_theme_accent" to share(S),
        // Word learning and the word card.
        "auto_pronounce" to share(B),
        "pronunciation_volume" to share(F),
        "phone_voice" to share(B),
        "meaning_order" to share(S),
        "word_learning_mode_enabled" to share(B),
        "word_learning_target" to share(S),
        "tap_to_learn_enabled" to share(B),
        "word_learning_active_only" to share(B),
        "kanji_details" to share(B),
        "word_card_kanji_open" to share(B),
        "word_card_hanzi_open" to share(B),
        "word_card_grammar_open" to share(B),
        "known_words_mode" to share(S),
        "known_words_after" to share(I),
        "known_words_offered" to backup(B),
        "mark_new_words" to share(B),
        // Study tools and flashcards; which flashcard app and deck depends on this phone's apps.
        "study_play_mode" to share(S),
        "study_repeat_count" to share(I),
        "skip_silence" to share(S),
        "blur_box_enabled" to share(B),
        "line_loop_button" to share(B),
        "page_token_captions" to backup(S),
        "anki_card_audio" to share(B),
        "anki_card_picture" to share(B),
        "anki_card_front" to share(S),
        "anki_card_front_sound" to share(B),
        "anki_card_word_sound" to share(S),
        "anki_card_sentence_sound" to share(S),
        "anki_card_picture_place" to share(S),
        "ankidroid_enabled" to backup(B),
        "send_to_app" to backup(S),
        "ankiconnect_deck" to backup(S),
        // Ads and sponsors.
        "skip_ads" to share(B),
        "skip_sponsors" to share(B),
        "always_skip_sponsors" to share(B),
        // Progress, updates and TV.
        "daily_goal_minutes" to backup(I),
        "daily_goal_prompt_completed" to backup(B),
        "app_auto_update" to backup(B),
        "update_reminders" to backup(B),
        "tv_layout" to backup(B),
        // AI: backups only, so a shared link can never turn on anything that uses an API key.
        "ai_subtitle_translation" to backup(B),
        "ai_subtitle_translation_only" to backup(B),
        "ai_translation_model" to backup(S),
        "ai_translation_thinking" to backup(S),
        "meaning_in_context" to backup(B),
        "ai_enabled" to backup(B),
        "ai_provider" to backup(S),
        "ai_history_retention" to backup(S),
        "ai_thinking" to backup(S),
        "ai_memory_enabled" to backup(B),
        "ai_youtube_search" to backup(B),
        "ai_web_search" to backup(S),
        "ai_screen" to backup(B),
        // Never: a planted address could send the AI keys to someone else's server, and full access
        // must be chosen again on purpose.
        "ai_custom_base_url" to never(S),
        "ai_full_access" to never(B),
        "ai_intro_seen" to never(B),
        "ai_news_seen" to never(I),
        // Never: this phone's state, sign-ins and one-time notices.
        "app_language" to never(S),
        "guide_completed" to never(B),
        "onboarding_completed" to never(B),
        "last_browser_url" to never(S),
        "browser_tabs" to never(S),
        "different_tab_accounts" to never(B),
        "drawer_tabs_open" to never(B),
        "tv_recent_searches" to never(S),
        "page_token_needed" to never(B),
        "karaoke_timing_mode" to never(S),
        "app_update_last_check" to never(I),
        "update_reminder_security_fix" to never(I),
        "update_reminder_security_since" to never(I),
        "update_reminder_shown_at" to never(I),
        "update_reminder_skipped" to never(S),
        "update_reminder_version" to never(S),
        "remote_notice_closed" to never(S),
    )

/** The AI model picked for each service (`ai_model_gemini`, …), kept in backups. */
internal const val AI_MODEL_KEY_PREFIX = "ai_model_"

/** The deck last picked for each word language (`deck_ja`, …), kept in backups. */
internal const val DECK_KEY_PREFIX = "deck_"

internal const val BACKUP_FORMAT = "DualSubReplayBackup"
internal const val BACKUP_VERSION = 1
internal const val SETUP_LINK_VERSION = 1

/** A whole backup, words and all, stays under this. */
internal const val MAX_BACKUP_BYTES = 24 * 1024 * 1024
private const val MAX_SETTING_TEXT = 4_000
private const val MAX_SETTING_FLOAT = 10.0
private const val MAX_SETTING_INT = 1_000_000L
private const val MAX_PROGRESS_ROWS = 50_000
private const val MAX_KNOWN_WORDS = 200_000
private const val MAX_STORIES = 50_000
private const val MAX_STORY_LENGTH = 2_000
private const val MAX_SETUP_LINK_BYTES = 64 * 1024
private val LANGUAGE_CODE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")
private val KEY_SUFFIX = Regex("[a-z0-9_-]{1,32}")

/** The address a shared setup opens: the landing site's page, which offers "Open in app". */
internal const val SETUP_PAGE_URL = "https://hoangkien1703.github.io/dual-sub-replay/setup/"
internal const val SETUP_APP_SCHEME = "dualsubreplay"
internal const val SETUP_APP_HOST = "setup"

/** The spec of [key] in [file], or null when it is not a setting this version knows. */
internal fun settingSpec(
    file: String,
    key: String,
): SettingSpec? =
    when (file) {
        APP_PREFERENCES_FILE, AI_PREFERENCES_FILE ->
            SETTING_SPECS[key]
                ?: if (file == AI_PREFERENCES_FILE && key.startsWith(AI_MODEL_KEY_PREFIX) &&
                    key.removePrefix(AI_MODEL_KEY_PREFIX).matches(KEY_SUFFIX)
                ) {
                    backup(S)
                } else {
                    null
                }
        DECK_PREFERENCES_FILE ->
            if (key.startsWith(DECK_KEY_PREFIX) && key.removePrefix(DECK_KEY_PREFIX).matches(LANGUAGE_CODE)) backup(S) else null
        else -> null
    }

/** True when a value of [scope] may go into a [shared] setup (otherwise: a backup). */
internal fun SettingScope.allowed(shared: Boolean): Boolean =
    when (this) {
        SettingScope.SHARE -> true
        SettingScope.BACKUP -> !shared
        SettingScope.NEVER -> false
    }

/**
 * [value] as [type] wants it, or null when it is the wrong type or out of range. Whole numbers
 * read from JSON may arrive as Int, Long or Double, so numbers are converted, never cast.
 */
internal fun checkedSettingValue(
    type: SettingType,
    value: Any?,
): Any? =
    when (type) {
        SettingType.BOOLEAN -> value as? Boolean
        SettingType.INT ->
            (value as? Number)
                ?.takeIf { it.toDouble() == Math.floor(it.toDouble()) && kotlin.math.abs(it.toDouble()) <= MAX_SETTING_INT }
                ?.toInt()
        SettingType.FLOAT ->
            (value as? Number)
                ?.toDouble()
                ?.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_SETTING_FLOAT }
                ?.toFloat()
        SettingType.STRING -> (value as? String)?.takeIf { it.length <= MAX_SETTING_TEXT }
    }

/**
 * The settings of one preference file that may travel, from [stored] (SharedPreferences.getAll()).
 * Values whose stored type differs from the spec are left out, like keys this version does not know.
 */
internal fun exportSettings(
    file: String,
    stored: Map<String, *>,
    shared: Boolean,
): Map<String, Any> =
    stored.entries
        .mapNotNull { (key, value) ->
            val spec = settingSpec(file, key) ?: return@mapNotNull null
            if (!spec.scope.allowed(shared)) return@mapNotNull null
            // Stored Longs (timestamps) only exist under NEVER keys; an INT setting is an Int.
            val checked = checkedSettingValue(spec.type, value) ?: return@mapNotNull null
            if (spec.type == SettingType.INT && value !is Int) return@mapNotNull null
            if (spec.type == SettingType.FLOAT && value !is Float) return@mapNotNull null
            key to checked
        }.toMap()
        .toSortedMap()

/** The settings in [json] this version may store for [file], each of the right type; the rest is skipped. */
internal fun importSettings(
    file: String,
    json: JSONObject?,
    shared: Boolean,
): Map<String, Any> {
    if (json == null) return emptyMap()
    val values = sortedMapOf<String, Any>()
    json.keys().forEach { key ->
        val spec = settingSpec(file, key) ?: return@forEach
        if (!spec.scope.allowed(shared)) return@forEach
        checkedSettingValue(spec.type, json.opt(key))?.let { values[key] = it }
    }
    return values
}

internal fun settingsJson(values: Map<String, Any>): JSONObject =
    JSONObject().apply {
        values.forEach { (key, value) ->
            put(key, if (value is Float) value.toDouble() else value)
        }
    }

/** The keys of [incoming] whose value differs from [current] (a missing key counts as different). */
internal fun changedSettingKeys(
    current: Map<String, *>,
    incoming: Map<String, Any>,
): List<String> = incoming.keys.filter { key -> current[key] != incoming.getValue(key) }

// ---------------------------------------------------------------------------------------------
// Backup file

internal data class BackupContents(
    val createdAtMs: Long,
    val appVersion: String,
    /** Per preference file; null when the backup has no settings. */
    val settings: Map<String, Map<String, Any>>?,
    /** Null when the backup has no Practice words. */
    val words: List<SavedWord>?,
    val knownWords: Map<String, Set<String>> = emptyMap(),
    /** Stories by language ("ja" kanji, "zh" hanzi), then by character. */
    val stories: Map<String, Map<String, String>> = emptyMap(),
    /** Null when the backup has no Progress. */
    val progress: List<ImmersionDay>?,
)

internal fun encodeBackup(contents: BackupContents): String {
    val json =
        JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("version", BACKUP_VERSION)
            put("createdAt", contents.createdAtMs)
            put("appVersion", contents.appVersion)
            contents.settings?.let { files ->
                put(
                    "settings",
                    JSONObject().apply {
                        files.toSortedMap().forEach { (file, values) -> put(file, settingsJson(values)) }
                    },
                )
            }
            contents.words?.let { words ->
                put("practice", JSONObject(exportBackup(words)))
                put(
                    "knownWords",
                    JSONObject().apply {
                        contents.knownWords.toSortedMap().forEach { (language, keys) -> put(language, JSONArray(keys.sorted())) }
                    },
                )
                put(
                    "stories",
                    JSONObject().apply {
                        contents.stories.toSortedMap().forEach { (language, stories) ->
                            put(language, JSONObject().apply { stories.toSortedMap().forEach { (literal, story) -> put(literal, story) } })
                        }
                    },
                )
            }
            contents.progress?.let { days ->
                put(
                    "progress",
                    JSONArray().apply {
                        days.sortedWith(compareBy({ it.day }, { it.language })).forEach { day ->
                            put(
                                JSONObject().apply {
                                    put("day", day.day.toString())
                                    put("language", day.language)
                                    put("ms", day.ms)
                                    put("videos", day.videos)
                                },
                            )
                        }
                    },
                )
            }
        }
    val text = json.toString(1)
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup is larger than 24 MiB." }
    return text
}

internal fun readBackupText(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= MAX_BACKUP_BYTES) {
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_BACKUP_BYTES + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    require(output.size() <= MAX_BACKUP_BYTES) { "Backup is larger than 24 MiB." }
    return Charsets.UTF_8
        .newDecoder()
        .decode(java.nio.ByteBuffer.wrap(output.toByteArray()))
        .toString()
        .removePrefix("\uFEFF")
}

/** A backup file read back; anything this version does not know or trust is dropped. */
internal fun parseBackup(text: String): BackupContents {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup is larger than 24 MiB." }
    val json = JSONObject(text.removePrefix("\uFEFF"))
    require(json.optString("format") == BACKUP_FORMAT) { "This is not a Dual Sub Replay backup." }
    require(json.optInt("version") == BACKUP_VERSION) { "This backup is from a newer app version. Update the app first." }
    val settings =
        json.optJSONObject("settings")?.let { files ->
            listOf(APP_PREFERENCES_FILE, AI_PREFERENCES_FILE, DECK_PREFERENCES_FILE)
                .associateWith { file -> importSettings(file, files.optJSONObject(file), shared = false) }
                .filterValues { it.isNotEmpty() }
        }
    val words =
        json.optJSONObject("practice")?.let { practice ->
            parseTransfer(practice.toString()).backup ?: emptyList()
        }
    val knownWords =
        json
            .optJSONObject("knownWords")
            ?.let { languages ->
                var total = 0
                languages
                    .keys()
                    .asSequence()
                    .filter { it.matches(LANGUAGE_CODE) }
                    .associateWith { language ->
                        val list = languages.optJSONArray(language) ?: JSONArray()
                        total += list.length()
                        require(total <= MAX_KNOWN_WORDS) { "Too many known words." }
                        val words = (0 until list.length()).mapNotNull { list.opt(it) as? String }
                        words.filter { it.isNotBlank() && it.length <= 200 }.toSet()
                    }.filterValues { it.isNotEmpty() }
            }.orEmpty()
    val stories =
        json
            .optJSONObject("stories")
            ?.let { languages ->
                var total = 0
                languages
                    .keys()
                    .asSequence()
                    .filter { it == "ja" || it == "zh" }
                    .associateWith { language ->
                        val entries = languages.optJSONObject(language) ?: JSONObject()
                        total += entries.length()
                        require(total <= MAX_STORIES) { "Too many stories." }
                        entries
                            .keys()
                            .asSequence()
                            .filter { it.codePointCount(0, it.length) == 1 }
                            .mapNotNull { literal ->
                                (entries.opt(literal) as? String)
                                    ?.replace(Regex("[\\t\\r\\n]+"), " ")
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() && it.length <= MAX_STORY_LENGTH }
                                    ?.let { literal to it }
                            }.toMap()
                    }.filterValues { it.isNotEmpty() }
            }.orEmpty()
    val progress =
        json.optJSONArray("progress")?.let { rows ->
            require(rows.length() <= MAX_PROGRESS_ROWS) { "Too many progress days." }
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val day = runCatching { LocalDate.parse(row.optString("day")) }.getOrNull() ?: return@mapNotNull null
                val language = row.optString("language").takeIf { it.matches(LANGUAGE_CODE) } ?: return@mapNotNull null
                val ms = (row.opt("ms") as? Number)?.toLong()?.takeIf { it in 0..DAY_MS } ?: return@mapNotNull null
                val videos = (row.opt("videos") as? Number)?.toInt()?.takeIf { it in 0..100_000 } ?: 0
                ImmersionDay(day, language, ms, videos)
            }
        }
    return BackupContents(
        createdAtMs = json.optLong("createdAt"),
        appVersion = json.optString("appVersion").take(40),
        settings = settings,
        words = words,
        knownWords = knownWords,
        stories = stories,
        progress = progress,
    )
}

/** True when [candidate] is further along in its reviews than [current] (longer interval, then later due). */
internal fun reviewedFurther(
    candidate: SavedWord,
    current: SavedWord,
): Boolean =
    candidate.intervalMs > current.intervalMs ||
        (candidate.intervalMs == current.intervalMs && candidate.dueAt > current.dueAt)

internal data class WordRestore(
    /** New words, then saved words the backup is further along in; write these with replace. */
    val words: List<SavedWord>,
    val added: Int,
    /** Words already saved on this phone; the newer review wins. */
    val alreadySaved: Int,
)

/** What restoring [incoming] does to [existing]: new words are added, and a saved word keeps the newer review. */
internal fun planWordRestore(
    existing: List<SavedWord>,
    incoming: List<SavedWord>,
): WordRestore {
    val byId = existing.associateBy { it.id }
    val unique = incoming.distinctBy { it.id }
    val added = unique.filter { it.id !in byId }
    val newer = unique.filter { word -> byId[word.id]?.let { reviewedFurther(word, it) } == true }
    return WordRestore(added + newer, added.size, unique.size - added.size)
}

internal data class ProgressRestore(
    /** Rows to merge, each keeping the higher time and video count of the two. */
    val days: List<ImmersionDay>,
    /** Study days (any language) this phone did not have. */
    val newDays: Int,
)

internal fun planProgressRestore(
    existing: List<ImmersionDay>,
    incoming: List<ImmersionDay>,
): ProgressRestore {
    val known = existing.mapTo(mutableSetOf()) { it.day }
    val merged =
        incoming
            .groupBy { it.day to it.language }
            .map { (_, rows) -> rows.maxBy { it.ms } }
    return ProgressRestore(merged, merged.map { it.day }.toSet().count { it !in known })
}

// ---------------------------------------------------------------------------------------------
// Shared setup link

/** A link to [SETUP_PAGE_URL] carrying the [SettingScope.SHARE] settings after `#`, which browsers never send. */
internal fun encodeSetupLink(values: Map<String, Any>): String {
    val json =
        JSONObject().apply {
            put("v", SETUP_LINK_VERSION)
            put("s", settingsJson(values.filterKeys { SETTING_SPECS[it]?.scope == SettingScope.SHARE }))
        }
    val input = json.toString().toByteArray(Charsets.UTF_8)
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    val packed =
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    return "$SETUP_PAGE_URL#$SETUP_LINK_VERSION." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
}

/**
 * The setup code in [text]: from the landing site's link (after `#`), the app link the site opens
 * (`dualsubreplay://setup?d=`), or the bare code. Null when [text] holds none.
 */
internal fun setupCodeIn(text: String): String? {
    val trimmed = text.trim()
    val code =
        when {
            trimmed.startsWith("$SETUP_APP_SCHEME://$SETUP_APP_HOST") ->
                trimmed
                    .substringAfter("?", "")
                    .split('&')
                    .firstOrNull { it.startsWith("d=") }
                    ?.removePrefix("d=")
            SETUP_PAGE_URL.removeSuffix("/") in trimmed && '#' in trimmed -> trimmed.substringAfter('#').substringBefore(' ')
            else -> trimmed
        } ?: return null
    return code.takeIf { it.matches(Regex("$SETUP_LINK_VERSION\\.[A-Za-z0-9_-]{8,}")) }
}

/** The shared settings in a setup [code]; throws when it is broken or from a newer app. */
internal fun decodeSetupCode(code: String): Map<String, Any> {
    require(code.startsWith("$SETUP_LINK_VERSION.")) { "This setup link is from a newer app version." }
    val packed = Base64.getUrlDecoder().decode(code.substringAfter('.'))
    val inflater = Inflater(true)
    val text =
        try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, count)
                require(out.size() <= MAX_SETUP_LINK_BYTES) { "This setup link is too large." }
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    val json = JSONObject(text)
    require(json.optInt("v") == SETUP_LINK_VERSION) { "This setup link is from a newer app version." }
    return importSettings(APP_PREFERENCES_FILE, json.optJSONObject("s"), shared = true)
}

/** What undoing a shared setup puts back: each changed key's old value, or null where it was unset. */
internal fun encodeUndo(previous: Map<String, Any?>): String =
    JSONObject()
        .apply {
            previous.toSortedMap().forEach { (key, value) ->
                put(
                    key,
                    if (value == null) {
                        JSONObject.NULL
                    } else if (value is Float) {
                        value.toDouble()
                    } else {
                        value
                    },
                )
            }
        }.toString()

internal fun decodeUndo(text: String?): Map<String, Any?>? {
    if (text.isNullOrBlank()) return null
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val values = mutableMapOf<String, Any?>()
    json.keys().forEach { key ->
        val spec = SETTING_SPECS[key]?.takeIf { it.scope == SettingScope.SHARE } ?: return@forEach
        if (json.isNull(key)) values[key] = null else checkedSettingValue(spec.type, json.opt(key))?.let { values[key] = it }
    }
    return values
}
