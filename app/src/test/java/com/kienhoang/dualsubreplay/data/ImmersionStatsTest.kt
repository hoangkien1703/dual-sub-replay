package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class ImmersionStatsTest {
    // Wednesday.
    private val today = LocalDate.of(2026, 9, 30)
    private val monday = DayOfWeek.MONDAY

    private fun day(
        date: LocalDate,
        minutes: Long,
        language: String = "ja",
        videos: Int = 1,
    ) = ImmersionDay(date, language, minutes * 60_000, videos)

    @Test fun trackerCreditsWallTimeWhileMediaAdvances() {
        val tracker = ImmersionTimeTracker()
        assertEquals(0L, tracker.onTick(1_000, 10_000, playing = true))
        assertEquals(33L, tracker.onTick(1_033, 10_033, playing = true))
        // Twice the speed still credits the real time spent watching.
        assertEquals(33L, tracker.onTick(1_066, 10_099, playing = true))
    }

    @Test fun trackerIgnoresPausedTimeAndResumesCleanly() {
        val tracker = ImmersionTimeTracker()
        tracker.onTick(0, 0, playing = true)
        assertEquals(33L, tracker.onTick(33, 33, playing = true))
        assertEquals(0L, tracker.onTick(66, 33, playing = false))
        assertEquals(0L, tracker.onTick(5_000, 33, playing = false))
        // Only the time after resuming is credited, not the pause before it.
        assertEquals(33L, tracker.onTick(5_033, 66, playing = true))
        assertEquals(33L, tracker.onTick(5_066, 99, playing = true))
    }

    @Test fun trackerStopsCreditingWhenMediaStallsForMoreThanASecond() {
        val tracker = ImmersionTimeTracker()
        tracker.onTick(0, 0, playing = true)
        assertEquals(33L, tracker.onTick(33, 33, playing = true))
        var credited = 0L
        var now = 33L
        repeat(60) {
            now += 33
            credited += tracker.onTick(now, 33, playing = true)
        }
        assertTrue("stalled credit $credited", credited <= 1_000L)
    }

    @Test fun trackerIgnoresSeeksAndLongGaps() {
        val tracker = ImmersionTimeTracker()
        tracker.onTick(0, 0, playing = true)
        assertEquals(0L, tracker.onTick(33, 120_000, playing = true))
        assertEquals(0L, tracker.onTick(66, 1_000, playing = true))
        assertEquals(0L, tracker.onTick(10_000, 11_000, playing = true))
        assertEquals(33L, tracker.onTick(10_033, 11_033, playing = true))
    }

    @Test fun trackerResetForgetsThePreviousTick() {
        val tracker = ImmersionTimeTracker()
        tracker.onTick(0, 0, playing = true)
        tracker.reset()
        assertEquals(0L, tracker.onTick(33, 33, playing = true))
    }

    @Test fun accumulatorGroupsByDayAndLanguageAndCountsEachVideoOnce() {
        val accumulator = ImmersionAccumulator()
        accumulator.add(today, "ja", "a", 1_000)
        accumulator.add(today, "ja", "a", 2_000)
        accumulator.add(today, "ja", "b", 500)
        accumulator.add(today, "en", "a", 700)
        accumulator.add(today.plusDays(1), "ja", "a", 300)
        accumulator.add(today, "ja", "c", 0)
        val deltas = accumulator.drain(0).associateBy { it.day to it.language }
        assertEquals(ImmersionDelta(today, "ja", 3_500, 2), deltas[today to "ja"])
        assertEquals(ImmersionDelta(today, "en", 700, 1), deltas[today to "en"])
        assertEquals(ImmersionDelta(today.plusDays(1), "ja", 300, 1), deltas[today.plusDays(1) to "ja"])
        assertTrue(accumulator.drain(0).isEmpty())
        accumulator.add(today.plusDays(1), "ja", "a", 100)
        assertEquals(0, accumulator.drain(0).single().videos)
    }

    @Test fun accumulatorFlushesAboutEveryThirtySeconds() {
        val accumulator = ImmersionAccumulator()
        assertFalse(accumulator.shouldFlush(0))
        accumulator.add(today, "ja", "a", 1_000)
        assertFalse(accumulator.shouldFlush(IMMERSION_FLUSH_INTERVAL_MS - 1))
        assertTrue(accumulator.shouldFlush(IMMERSION_FLUSH_INTERVAL_MS))
        accumulator.drain(IMMERSION_FLUSH_INTERVAL_MS)
        assertFalse(accumulator.shouldFlush(IMMERSION_FLUSH_INTERVAL_MS * 3))
    }

    @Test fun languageComesFromTheCaptionTrackOrTheChosenLanguage() {
        assertEquals("ja", immersionLanguage("ja", "auto"))
        assertEquals("pt", immersionLanguage("pt-BR", "en"))
        assertEquals("he", immersionLanguage("iw", "auto"))
        assertEquals("ko", immersionLanguage(null, "ko"))
        assertNull(immersionLanguage(null, "auto"))
        assertNull(immersionLanguage("", ""))
    }

    @Test fun periodRangesFollowTheCalendar() {
        assertEquals(LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 4), periodRange(ImmersionPeriod.WEEK, today, monday))
        assertEquals(LocalDate.of(2026, 9, 27)..LocalDate.of(2026, 10, 3), periodRange(ImmersionPeriod.WEEK, today, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30), periodRange(ImmersionPeriod.MONTH, today, monday))
        assertEquals(LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 12, 31), periodRange(ImmersionPeriod.YEAR, today, monday))
        assertNull(periodRange(ImmersionPeriod.ALL, today, monday))
    }

    @Test fun totalsSumEachPeriod() {
        val days =
            listOf(
                day(today, 10),
                day(today, 5, language = "en"),
                day(LocalDate.of(2026, 9, 28), 20),
                day(LocalDate.of(2026, 9, 2), 30),
                day(LocalDate.of(2026, 2, 1), 40),
                day(LocalDate.of(2025, 12, 31), 50),
            )
        val totals = immersionTotals(days, today, monday)
        assertEquals(15 * 60_000L, totals.todayMs)
        assertEquals(35 * 60_000L, totals.weekMs)
        assertEquals(65 * 60_000L, totals.monthMs)
        assertEquals(105 * 60_000L, totals.yearMs)
        assertEquals(155 * 60_000L, totals.totalMs)
    }

    @Test fun languageBreakdownSortsByTimeWithinThePeriod() {
        val days =
            listOf(
                day(today, 10, "ja", videos = 2),
                day(today.minusDays(1), 10, "ja", videos = 1),
                day(today, 15, "en", videos = 1),
                day(LocalDate.of(2025, 1, 1), 100, "ko"),
            )
        assertEquals(
            listOf(LanguageTime("ja", 20 * 60_000L, 3), LanguageTime("en", 15 * 60_000L, 1)),
            languageBreakdown(days, ImmersionPeriod.WEEK, today, monday),
        )
        assertEquals("ko", languageBreakdown(days, ImmersionPeriod.ALL, today, monday).first().language)
    }

    @Test fun barsCoverEveryBucketOfThePeriod() {
        val days = listOf(day(today, 10), day(today, 5, "en"), day(LocalDate.of(2026, 3, 3), 7), day(LocalDate.of(2024, 5, 5), 1))
        val week = periodBars(days, ImmersionPeriod.WEEK, today, monday, Locale.ENGLISH)
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), week.map { it.label })
        assertEquals(15 * 60_000L, week[2].ms)
        assertTrue(week[2].current)
        assertEquals(30, periodBars(days, ImmersionPeriod.MONTH, today, monday, Locale.ENGLISH).size)
        val year = periodBars(days, ImmersionPeriod.YEAR, today, monday, Locale.ENGLISH)
        assertEquals(12, year.size)
        assertEquals(listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"), year.map { it.label })
        assertEquals(7 * 60_000L, year[2].ms)
        assertTrue(year[8].current)
        val all = periodBars(days, ImmersionPeriod.ALL, today, monday, Locale.ENGLISH)
        assertEquals(listOf("2024", "2025", "2026"), all.map { it.label })
        assertEquals(listOf(60_000L, 0L, 22 * 60_000L), all.map { it.ms })
        assertEquals(listOf("2026"), periodBars(emptyList(), ImmersionPeriod.ALL, today, monday, Locale.ENGLISH).map { it.label })
    }

    @Test fun streakCountsGoalDaysAndAnUnfinishedTodayDoesNotBreakIt() {
        val days = listOf(day(today.minusDays(1), 20), day(today.minusDays(2), 25), day(today.minusDays(3), 5), day(today, 3))
        assertEquals(2, currentStreak(days, today, goalMinutes = 15))
        assertEquals(3, currentStreak(days + day(today, 15, "en"), today, goalMinutes = 15))
        // Without a goal, any day with at least a minute counts.
        assertEquals(4, currentStreak(days, today, goalMinutes = 0))
        assertEquals(0, currentStreak(listOf(day(today.minusDays(2), 60)), today, goalMinutes = 15))
    }

    @Test fun goalProgressIsClamped() {
        assertEquals(0f, goalProgress(10 * 60_000L, 0), 0f)
        assertEquals(0.5f, goalProgress(10 * 60_000L, 20), 0.001f)
        assertEquals(1f, goalProgress(90 * 60_000L, 20), 0f)
    }

    @Test fun durationsReadNaturally() {
        // English: "0 min", "<1 min", "45 min", "1 h", "1 h 5 min", "120 h".
        assertEquals(ImmersionDuration(ImmersionDurationStyle.MINUTES, 0, 0), immersionDuration(0))
        assertEquals(ImmersionDuration(ImmersionDurationStyle.UNDER_A_MINUTE, 0, 0), immersionDuration(59_000))
        assertEquals(ImmersionDuration(ImmersionDurationStyle.MINUTES, 0, 45), immersionDuration(45 * 60_000L))
        assertEquals(ImmersionDuration(ImmersionDurationStyle.HOURS, 1, 0), immersionDuration(60 * 60_000L))
        assertEquals(ImmersionDuration(ImmersionDurationStyle.HOURS_AND_MINUTES, 1, 5), immersionDuration(65 * 60_000L))
        assertEquals(ImmersionDuration(ImmersionDurationStyle.HOURS, 120, 0), immersionDuration(120 * 3_600_000L + 30 * 60_000L))
    }

    @Test fun storedGoalOnlyAcceptsOfferedChoices() {
        assertEquals(20, storedDailyGoalMinutes(20))
        assertEquals(0, storedDailyGoalMinutes(0))
        assertEquals(0, storedDailyGoalMinutes(17))
        assertEquals(0, storedDailyGoalMinutes(-5))
    }

    @Test fun goalStepIsShownOnlyToUsersWhoHaveNotFinishedTheGuideBefore() {
        // New user: guide not finished yet, so the step will follow the guide.
        assertFalse(initialDailyGoalPromptCompleted(preferenceExists = false, preferenceValue = false, guideCompleted = false))
        // Existing user upgrading after finishing the guide is not interrupted.
        assertTrue(initialDailyGoalPromptCompleted(preferenceExists = false, preferenceValue = false, guideCompleted = true))
        // Finishing the guide stores false, so the step still shows after a restart.
        assertFalse(initialDailyGoalPromptCompleted(preferenceExists = true, preferenceValue = false, guideCompleted = true))
        assertTrue(initialDailyGoalPromptCompleted(preferenceExists = true, preferenceValue = true, guideCompleted = true))
    }
}
