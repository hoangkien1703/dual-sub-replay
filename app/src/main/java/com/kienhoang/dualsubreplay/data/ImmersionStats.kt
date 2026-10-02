package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Watched time for one local day and learning language. */
data class ImmersionDay(
    val day: LocalDate,
    val language: String,
    val ms: Long,
    val videos: Int,
)

/** Time waiting to be written; [videos] counts videos first watched in this batch. */
internal data class ImmersionDelta(
    val day: LocalDate,
    val language: String,
    val ms: Long,
    val videos: Int,
)

enum class ImmersionPeriod { WEEK, MONTH, YEAR, ALL }

internal data class ImmersionTotals(
    val todayMs: Long,
    val weekMs: Long,
    val monthMs: Long,
    val yearMs: Long,
    val totalMs: Long,
)

internal data class LanguageTime(
    val language: String,
    val ms: Long,
    val videos: Int,
)

internal data class ImmersionBar(
    val label: String,
    val ms: Long,
    val current: Boolean,
)

internal const val IMMERSION_MAX_TICK_GAP_MS = 1_000L
internal const val IMMERSION_FLUSH_INTERVAL_MS = 30_000L
internal const val IMMERSION_STREAK_MINIMUM_MS = 60_000L
internal val DAILY_GOAL_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)
internal const val DEFAULT_DAILY_GOAL_MINUTES = 15

/**
 * Turns the ~33 ms playback ticks into watched wall-clock time. A tick is credited only when the
 * video is playing, the page media clock moved forward recently, and the previous tick was close
 * enough that the app was not asleep. Seeks (a media jump much larger than the elapsed time) are
 * never credited.
 */
internal class ImmersionTimeTracker {
    private var lastTickAt: Long? = null
    private var lastMediaMs: Long? = null
    private var lastAdvanceAt: Long? = null

    fun onTick(
        nowMs: Long,
        mediaMs: Long,
        playing: Boolean,
    ): Long {
        val previousTick = lastTickAt
        val previousMedia = lastMediaMs
        lastTickAt = nowMs
        lastMediaMs = mediaMs
        if (!playing || previousTick == null || previousMedia == null) {
            lastAdvanceAt = null
            return 0L
        }
        val elapsed = nowMs - previousTick
        val advanced = mediaMs - previousMedia
        if (elapsed !in 1..IMMERSION_MAX_TICK_GAP_MS || advanced < 0 || advanced > elapsed * 4 + 500) {
            lastAdvanceAt = null
            return 0L
        }
        if (advanced > 0) lastAdvanceAt = nowMs
        val advanceAt = lastAdvanceAt ?: return 0L
        return if (nowMs - advanceAt <= IMMERSION_MAX_TICK_GAP_MS) elapsed else 0L
    }

    fun reset() {
        lastTickAt = null
        lastMediaMs = null
        lastAdvanceAt = null
    }
}

/** Batches credited time so the database is written about every 30 s, not on every tick. */
internal class ImmersionAccumulator {
    private val pendingMs = linkedMapOf<Pair<LocalDate, String>, Long>()
    private val pendingVideos = mutableMapOf<Pair<LocalDate, String>, Int>()
    private val countedVideos = mutableSetOf<Triple<LocalDate, String, String>>()
    private var lastFlushAt: Long? = null

    fun add(
        day: LocalDate,
        language: String,
        videoId: String,
        ms: Long,
    ) {
        if (ms <= 0) return
        val key = day to language
        pendingMs[key] = (pendingMs[key] ?: 0L) + ms
        if (countedVideos.add(Triple(day, language, videoId))) {
            pendingVideos[key] = (pendingVideos[key] ?: 0) + 1
        }
    }

    fun shouldFlush(nowMs: Long): Boolean {
        val last = lastFlushAt ?: nowMs.also { lastFlushAt = it }
        return pendingMs.isNotEmpty() && nowMs - last >= IMMERSION_FLUSH_INTERVAL_MS
    }

    fun drain(nowMs: Long): List<ImmersionDelta> {
        lastFlushAt = nowMs
        val deltas = pendingMs.map { (key, ms) -> ImmersionDelta(key.first, key.second, ms, pendingVideos[key] ?: 0) }
        pendingMs.clear()
        pendingVideos.clear()
        // Only today's videos can still be counted again; keep the set from growing forever.
        deltas.maxOfOrNull { it.day }?.let { latest -> countedVideos.removeAll { it.first < latest } }
        return deltas
    }
}

/** The caption track being read, or the chosen learning language while captions are still loading. */
internal fun immersionLanguage(
    resolvedSourceLanguage: String?,
    sourcePreference: String,
): String? {
    val resolved = resolvedSourceLanguage?.takeIf(String::isNotBlank)
    val preferred = sourcePreference.takeUnless { it.isBlank() || it == "auto" }
    return (resolved ?: preferred)?.let(TranslationLanguages::normalize)
}

internal fun startOfWeek(
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
): LocalDate = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

/** Inclusive range for [period], or null for all time. */
internal fun periodRange(
    period: ImmersionPeriod,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
): ClosedRange<LocalDate>? =
    when (period) {
        ImmersionPeriod.WEEK -> startOfWeek(today, firstDayOfWeek).let { it..it.plusDays(6) }
        ImmersionPeriod.MONTH -> today.withDayOfMonth(1)..today.withDayOfMonth(today.lengthOfMonth())
        ImmersionPeriod.YEAR -> today.withDayOfYear(1)..today.withDayOfYear(today.lengthOfYear())
        ImmersionPeriod.ALL -> null
    }

private fun List<ImmersionDay>.sumIn(range: ClosedRange<LocalDate>?): Long = filter { range == null || it.day in range }.sumOf { it.ms }

internal fun immersionTotals(
    days: List<ImmersionDay>,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
): ImmersionTotals =
    ImmersionTotals(
        todayMs = days.sumIn(today..today),
        weekMs = days.sumIn(periodRange(ImmersionPeriod.WEEK, today, firstDayOfWeek)),
        monthMs = days.sumIn(periodRange(ImmersionPeriod.MONTH, today, firstDayOfWeek)),
        yearMs = days.sumIn(periodRange(ImmersionPeriod.YEAR, today, firstDayOfWeek)),
        totalMs = days.sumOf { it.ms },
    )

/** Languages practiced in [period], most time first. */
internal fun languageBreakdown(
    days: List<ImmersionDay>,
    period: ImmersionPeriod,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
): List<LanguageTime> {
    val range = periodRange(period, today, firstDayOfWeek)
    return days
        .filter { range == null || it.day in range }
        .groupBy { it.language }
        .map { (language, rows) -> LanguageTime(language, rows.sumOf { it.ms }, rows.sumOf { it.videos }) }
        .filter { it.ms > 0 }
        .sortedWith(compareByDescending<LanguageTime> { it.ms }.thenBy { it.language })
}

/**
 * Chart buckets: week days, month days, year months, or one bar per year since the first record.
 * Week days and months are labeled with their narrow names in [locale] ("M", "T", ... in English).
 */
internal fun periodBars(
    days: List<ImmersionDay>,
    period: ImmersionPeriod,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    locale: Locale,
): List<ImmersionBar> {
    val byDay = days.groupBy { it.day }.mapValues { (_, rows) -> rows.sumOf { it.ms } }
    return when (period) {
        ImmersionPeriod.WEEK -> {
            val start = startOfWeek(today, firstDayOfWeek)
            (0L until 7L).map { offset ->
                val day = start.plusDays(offset)
                ImmersionBar(day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), byDay[day] ?: 0L, day == today)
            }
        }
        ImmersionPeriod.MONTH ->
            (1..today.lengthOfMonth()).map { dayOfMonth ->
                val day = today.withDayOfMonth(dayOfMonth)
                ImmersionBar(dayOfMonth.toString(), byDay[day] ?: 0L, day == today)
            }
        ImmersionPeriod.YEAR ->
            (1..12).map { month ->
                val ms = byDay.filterKeys { it.year == today.year && it.monthValue == month }.values.sum()
                ImmersionBar(Month.of(month).getDisplayName(TextStyle.NARROW, locale), ms, month == today.monthValue)
            }
        ImmersionPeriod.ALL -> {
            val firstYear = minOf(days.minOfOrNull { it.day.year } ?: today.year, today.year)
            (firstYear..today.year).map { year ->
                ImmersionBar(year.toString(), byDay.filterKeys { it.year == year }.values.sum(), year == today.year)
            }
        }
    }
}

/**
 * Consecutive days that reached the goal (or had at least one minute without a goal), ending today.
 * Today only extends the streak once it qualifies; an unfinished today never breaks it.
 */
internal fun currentStreak(
    days: List<ImmersionDay>,
    today: LocalDate,
    goalMinutes: Int,
): Int {
    val threshold = if (goalMinutes > 0) goalMinutes * 60_000L else IMMERSION_STREAK_MINIMUM_MS
    val byDay = days.groupBy { it.day }.mapValues { (_, rows) -> rows.sumOf { it.ms } }
    var day = if ((byDay[today] ?: 0L) >= threshold) today else today.minusDays(1)
    var streak = 0
    while ((byDay[day] ?: 0L) >= threshold) {
        streak += 1
        day = day.minusDays(1)
    }
    return streak
}

internal fun goalProgress(
    todayMs: Long,
    goalMinutes: Int,
): Float = if (goalMinutes <= 0) 0f else (todayMs.toFloat() / (goalMinutes * 60_000f)).coerceIn(0f, 1f)

/** Which shape a watched duration is shown in; the UI turns it into localized text. */
internal enum class ImmersionDurationStyle { UNDER_A_MINUTE, MINUTES, HOURS, HOURS_AND_MINUTES }

internal data class ImmersionDuration(
    val style: ImmersionDurationStyle,
    val hours: Long,
    val minutes: Long,
)

/**
 * "0 min", "45 min", "1 h 5 min", "12 h" in English. Seconds are dropped; under a minute reads "<1 min";
 * from 100 hours on the minutes are dropped too. [ImmersionDuration.minutes] is the rest after full hours.
 */
internal fun immersionDuration(ms: Long): ImmersionDuration {
    val minutes = ms.coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        ms <= 0 -> ImmersionDuration(ImmersionDurationStyle.MINUTES, 0, 0)
        minutes == 0L -> ImmersionDuration(ImmersionDurationStyle.UNDER_A_MINUTE, 0, 0)
        hours == 0L -> ImmersionDuration(ImmersionDurationStyle.MINUTES, 0, minutes)
        rest == 0L || hours >= 100 -> ImmersionDuration(ImmersionDurationStyle.HOURS, hours, 0)
        else -> ImmersionDuration(ImmersionDurationStyle.HOURS_AND_MINUTES, hours, rest)
    }
}

internal fun storedDailyGoalMinutes(value: Int): Int = if (value in DAILY_GOAL_CHOICES) value else 0

/** Users who finished the guide before the goal step existed are not interrupted after upgrading. */
internal fun initialDailyGoalPromptCompleted(
    preferenceExists: Boolean,
    preferenceValue: Boolean,
    guideCompleted: Boolean,
): Boolean = if (preferenceExists) preferenceValue else guideCompleted
