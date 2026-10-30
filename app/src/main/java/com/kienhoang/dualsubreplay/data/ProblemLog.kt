package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Which part of the app a problem came from. Its label is English: the report goes to the project's GitHub. */
internal enum class ProblemArea(
    val label: String,
) {
    GOOGLE_TRANSLATE("Google Translate"),
    CAPTIONS("Captions"),
    APP_UPDATE("App update"),
    REMOTE_SWITCHES("Remote switches"),
}

/** One problem, [count] times in a row, the last at [timeMs]. */
internal data class ProblemEntry(
    val timeMs: Long,
    val area: ProblemArea,
    val code: String,
    val count: Int = 1,
)

internal const val MAX_PROBLEM_ENTRIES = 50
private const val MAX_PROBLEM_CODE_CHARS = 160

/** Report a problem's GitHub address stays well under what browsers and GitHub accept. */
internal const val MAX_PROBLEM_REPORT_URL_CHARS = 6_000

/** The problems shown in a report; older ones stay in the log only. */
private const val PROBLEMS_IN_REPORT = 20

private val UNSAFE_CODE_CHARS = Regex("[^A-Za-z0-9 _.:/()=,;+-]")

/**
 * A problem's code: callers pass only step names, HTTP statuses, exception class names and Google
 * addresses (never video IDs, titles, subtitle or translated text); this also drops any other
 * character and long text, so a code stays a short technical label.
 */
internal fun problemCode(raw: String): String = raw.replace(UNSAFE_CODE_CHARS, "_").trim().take(MAX_PROBLEM_CODE_CHARS)

/** [entries] with one more problem; the same problem again only counts up. */
internal fun withProblem(
    entries: List<ProblemEntry>,
    area: ProblemArea,
    code: String,
    nowMs: Long,
    max: Int = MAX_PROBLEM_ENTRIES,
): List<ProblemEntry> {
    val last = entries.lastOrNull()
    if (last != null && last.area == area && last.code == code) {
        return entries.dropLast(1) + last.copy(timeMs = nowMs, count = last.count + 1)
    }
    return (entries + ProblemEntry(nowMs, area, code)).takeLast(max)
}

internal fun encodeProblems(entries: List<ProblemEntry>): String =
    entries.joinToString("") { "${it.timeMs}\t${it.area.name}\t${it.count}\t${it.code}\n" }

/** The saved log; a damaged line is left out. */
internal fun decodeProblems(text: String): List<ProblemEntry> =
    text
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) return@mapNotNull null
            val area = ProblemArea.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val time = parts[0].toLongOrNull() ?: return@mapNotNull null
            val count = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            ProblemEntry(time, area, problemCode(parts[3]), count)
        }.toList()
        .takeLast(MAX_PROBLEM_ENTRIES)

/** What Report a problem shows and sends: the app, Android and phone, then the recent problems. */
internal fun problemReport(
    appVersion: String,
    androidVersion: String,
    device: String,
    entries: List<ProblemEntry>,
): String =
    buildString {
        appendLine("App: $appVersion")
        appendLine("Android: $androidVersion")
        appendLine("Phone: $device")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("No problems recorded recently.")
        } else {
            appendLine("Recent problems (UTC, newest last):")
            entries.takeLast(PROBLEMS_IN_REPORT).forEach { entry ->
                val time = Instant.ofEpochMilli(entry.timeMs).truncatedTo(ChronoUnit.MINUTES)
                val times = if (entry.count > 1) " (x${entry.count})" else ""
                appendLine("- $time ${entry.area.label}: ${entry.code}$times")
            }
        }
    }.trimEnd()

private fun urlEncoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * A new GitHub issue from the bug report form with [report] filled in. The person reads and sends it
 * themselves; the oldest problems are left out until the address fits [MAX_PROBLEM_REPORT_URL_CHARS].
 */
internal fun problemIssueUrl(
    report: String,
    appVersion: String,
    device: String,
): String {
    val base =
        "https://github.com/$UPDATE_REPOSITORY/issues/new?template=bug_report.yml" +
            "&app-version=${urlEncoded(appVersion)}&device=${urlEncoded(device)}&evidence="
    val lines = report.lines().toMutableList()
    while (base.length + urlEncoded(lines.joinToString("\n")).length > MAX_PROBLEM_REPORT_URL_CHARS) {
        val oldest = lines.indexOfFirst { it.startsWith("- ") }
        if (oldest < 0) break
        lines.removeAt(oldest)
    }
    return (base + urlEncoded(lines.joinToString("\n"))).take(MAX_PROBLEM_REPORT_URL_CHARS)
}

/**
 * The app's private problem log (owner decision 2026-10-30): no server and no automatic upload. It
 * keeps the last [MAX_PROBLEM_ENTRIES] problems in the cache folder, which Android leaves out of
 * backups and may clear; Report a problem shows them so a person can send them on GitHub.
 */
internal object ProblemLog {
    private val mutableEntries = MutableStateFlow<List<ProblemEntry>>(emptyList())
    val entries: StateFlow<List<ProblemEntry>> = mutableEntries.asStateFlow()

    @Volatile private var file: File? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Reads the saved log once; problems recorded before this are kept after it. */
    fun init(cacheDirectory: File) {
        if (file != null) return
        val saved = File(cacheDirectory, "problem-log.txt")
        file = saved
        writer.launch {
            val loaded = runCatching { if (saved.exists()) decodeProblems(saved.readText()) else emptyList() }.getOrDefault(emptyList())
            mutableEntries.update { (loaded + it).takeLast(MAX_PROBLEM_ENTRIES) }
        }
    }

    fun record(
        area: ProblemArea,
        code: String,
    ) {
        mutableEntries.update { withProblem(it, area, problemCode(code), System.currentTimeMillis()) }
        save()
    }

    fun clear() {
        mutableEntries.value = emptyList()
        save()
    }

    private fun save() {
        val saved = file ?: return
        writer.launch { runCatching { saved.writeText(encodeProblems(entries.value)) } }
    }
}
