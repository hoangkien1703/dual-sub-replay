package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.DAILY_GOAL_CHOICES
import com.kienhoang.dualsubreplay.data.ImmersionBar
import com.kienhoang.dualsubreplay.data.ImmersionDay
import com.kienhoang.dualsubreplay.data.ImmersionDurationStyle
import com.kienhoang.dualsubreplay.data.ImmersionPeriod
import com.kienhoang.dualsubreplay.data.ImmersionRepository
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.currentStreak
import com.kienhoang.dualsubreplay.data.goalProgress
import com.kienhoang.dualsubreplay.data.immersionDuration
import com.kienhoang.dualsubreplay.data.immersionTotals
import com.kienhoang.dualsubreplay.data.languageBreakdown
import com.kienhoang.dualsubreplay.data.periodBars
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields

@Composable
internal fun ProgressScreen(
    repository: ImmersionRepository,
    savedWords: List<SavedWord>,
    goalMinutes: Int,
    onGoalChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val days by repository.days.collectAsStateWithLifecycle()
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            repository.refresh()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            ProgressContent(
                days = days,
                savedWordsByLanguage = remember(savedWords) { savedWordsByLanguage(savedWords) },
                goalMinutes = goalMinutes,
                today = LocalDate.now(),
                firstDayOfWeek = WeekFields.of(LocalConfiguration.current.locales[0]).firstDayOfWeek,
                error = if (loadFailed) stringResource(R.string.onboarding_progress_load_error) else null,
                onGoalChange = onGoalChange,
                onDismiss = onDismiss,
            )
        }
    }
}

internal fun savedWordsByLanguage(words: List<SavedWord>): Map<String, Int> =
    words.groupingBy { TranslationLanguages.normalize(it.wordLanguage) }.eachCount()

@Composable
@Suppress("LongMethod")
internal fun ProgressContent(
    days: List<ImmersionDay>,
    savedWordsByLanguage: Map<String, Int>,
    goalMinutes: Int,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    error: String? = null,
    onGoalChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var period by remember { mutableStateOf(ImmersionPeriod.WEEK) }
    var editingGoal by remember { mutableStateOf(false) }
    val totals = remember(days, today, firstDayOfWeek) { immersionTotals(days, today, firstDayOfWeek) }
    val streak = remember(days, today, goalMinutes) { currentStreak(days, today, goalMinutes) }
    val interfaceLocale = LocalContext.current.interfaceLocale()
    val bars =
        remember(days, period, today, firstDayOfWeek, interfaceLocale) {
            periodBars(days, period, today, firstDayOfWeek, interfaceLocale)
        }
    val languages = remember(days, period, today, firstDayOfWeek) { languageBreakdown(days, period, today, firstDayOfWeek) }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()).testTag("progress_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.onboarding_progress_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.onboarding_progress_close)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TodayCard(
            todayMs = totals.todayMs,
            goalMinutes = goalMinutes,
            streak = streak,
            onEditGoal = { editingGoal = true },
        )
        if (totals.totalMs <= 0) {
            Text(
                stringResource(R.string.onboarding_progress_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("progress_empty"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.onboarding_progress_this_week), totals.weekMs, Modifier.weight(1f).testTag("progress_week"))
            StatTile(stringResource(R.string.onboarding_progress_this_month), totals.monthMs, Modifier.weight(1f).testTag("progress_month"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.onboarding_progress_this_year), totals.yearMs, Modifier.weight(1f).testTag("progress_year"))
            StatTile(stringResource(R.string.onboarding_progress_total), totals.totalMs, Modifier.weight(1f).testTag("progress_total"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImmersionPeriod.entries.forEach { choice ->
                FilterChip(
                    selected = period == choice,
                    onClick = { period = choice },
                    label = { Text(stringResource(periodLabelRes(choice))) },
                    modifier = Modifier.testTag("progress_period_${choice.name.lowercase()}"),
                )
            }
        }
        ImmersionBarChart(bars)
        Text(stringResource(R.string.onboarding_progress_languages), style = MaterialTheme.typography.titleMedium)
        if (languages.isEmpty()) {
            Text(
                stringResource(R.string.onboarding_progress_no_time_in_period),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val periodMs = languages.sumOf { it.ms }.coerceAtLeast(1L)
        languages.forEach { language ->
            LanguageTimeRow(
                name = languageDisplayName(language.language, interfaceLocale),
                ms = language.ms,
                share = language.ms.toFloat() / periodMs,
                videos = language.videos,
                savedWords = savedWordsByLanguage[language.language] ?: 0,
                modifier = Modifier.testTag("progress_language_${language.language}"),
            )
        }
        Text(
            stringResource(R.string.onboarding_progress_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (editingGoal) {
        DailyGoalDialog(
            goalMinutes = goalMinutes,
            onConfirm = { minutes ->
                onGoalChange(minutes)
                editingGoal = false
            },
            onDismiss = { editingGoal = false },
        )
    }
}

@StringRes
private fun periodLabelRes(period: ImmersionPeriod): Int =
    when (period) {
        ImmersionPeriod.WEEK -> R.string.onboarding_progress_period_week
        ImmersionPeriod.MONTH -> R.string.onboarding_progress_period_month
        ImmersionPeriod.YEAR -> R.string.onboarding_progress_period_year
        ImmersionPeriod.ALL -> R.string.onboarding_progress_period_all
    }

@Composable
private fun TodayCard(
    todayMs: Long,
    goalMinutes: Int,
    streak: Int,
    onEditGoal: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.onboarding_progress_today),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                immersionDurationText(todayMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("progress_today"),
            )
            if (goalMinutes > 0) {
                val reached = todayMs >= goalMinutes * 60_000L
                LinearProgressIndicator(
                    progress = { goalProgress(todayMs, goalMinutes) },
                    modifier = Modifier.fillMaxWidth().testTag("progress_goal_bar"),
                )
                Text(
                    if (reached) {
                        stringResource(R.string.onboarding_progress_goal_reached, goalMinutes)
                    } else {
                        stringResource(R.string.onboarding_progress_goal, goalMinutes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("progress_goal_text"),
                )
            } else {
                Text(
                    stringResource(R.string.onboarding_progress_no_goal),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("progress_goal_text"),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (streak == 0) {
                        stringResource(R.string.onboarding_progress_streak_start)
                    } else {
                        pluralStringResource(R.plurals.onboarding_progress_streak_days, streak, streak)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("progress_streak"),
                )
                TextButton(onClick = onEditGoal, modifier = Modifier.testTag("progress_edit_goal")) {
                    Text(stringResource(if (goalMinutes > 0) R.string.onboarding_progress_change_goal else R.string.onboarding_goal_set))
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    ms: Long,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(immersionDurationText(ms), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ImmersionBarChart(bars: List<ImmersionBar>) {
    val maxMs = bars.maxOfOrNull { it.ms }?.coerceAtLeast(1L) ?: 1L
    // Thirty-one day labels do not fit on a phone; label every seventh bar and the current one.
    val sparseLabels = bars.size > 12
    Column(Modifier.fillMaxWidth().testTag("progress_chart")) {
        Row(
            Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(if (sparseLabels) 2.dp else 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { bar ->
                val fraction = if (bar.ms <= 0) 0f else (bar.ms.toFloat() / maxMs).coerceIn(0.03f, 1f)
                val description = stringResource(R.string.onboarding_progress_bar_description, bar.label, immersionDurationText(bar.ms))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .semantics { contentDescription = description }
                        .background(
                            if (bar.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (sparseLabels) 2.dp else 6.dp)) {
            bars.forEachIndexed { index, bar ->
                Text(
                    if (!sparseLabels || index % 7 == 0 || bar.current) bar.label else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bar.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LanguageTimeRow(
    name: String,
    ms: Long,
    share: Float,
    videos: Int,
    savedWords: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(immersionDurationText(ms), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        LinearProgressIndicator(progress = { share.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(
                R.string.onboarding_progress_language_counts,
                pluralStringResource(R.plurals.onboarding_progress_videos, videos, videos),
                pluralStringResource(R.plurals.onboarding_progress_saved_words, savedWords, savedWords),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A watched duration as shown to the user: "0 min", "<1 min", "45 min", "1 h 5 min", "12 h" in English. */
@Composable
internal fun immersionDurationText(ms: Long): String {
    val duration = immersionDuration(ms)
    return when (duration.style) {
        ImmersionDurationStyle.UNDER_A_MINUTE -> stringResource(R.string.onboarding_duration_under_minute)
        ImmersionDurationStyle.MINUTES -> stringResource(R.string.onboarding_duration_minutes, duration.minutes)
        ImmersionDurationStyle.HOURS -> stringResource(R.string.onboarding_duration_hours, duration.hours)
        ImmersionDurationStyle.HOURS_AND_MINUTES ->
            stringResource(R.string.onboarding_duration_hours_minutes, duration.hours, duration.minutes)
    }
}

@Composable
internal fun dailyGoalLabel(minutes: Int): String =
    if (minutes <= 0) {
        stringResource(R.string.onboarding_goal_off)
    } else {
        stringResource(R.string.onboarding_duration_minutes, minutes)
    }

/** Goal chips; [includeOff] adds "Off" so a goal can be cleared. */
@Composable
internal fun DailyGoalPicker(
    selected: Int,
    includeOff: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val choices = if (includeOff) listOf(0) + DAILY_GOAL_CHOICES else DAILY_GOAL_CHOICES
    val arrangement = if (centered) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp)
    FlowRow(modifier, horizontalArrangement = arrangement) {
        choices.forEach { minutes ->
            FilterChip(
                selected = selected == minutes,
                onClick = { onSelect(minutes) },
                label = { Text(dailyGoalLabel(minutes)) },
                modifier = Modifier.testTag("daily_goal_$minutes"),
            )
        }
    }
}

@Composable
private fun DailyGoalDialog(
    goalMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(goalMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.onboarding_goal_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_goal_dialog_body))
                DailyGoalPicker(selected = selected, includeOff = true, onSelect = { selected = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, modifier = Modifier.testTag("daily_goal_save")) {
                Text(stringResource(R.string.onboarding_goal_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.onboarding_goal_cancel)) } },
    )
}
