package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class ProblemLogTest {
    private val time = 1_800_000_000_000L

    @Test
    fun repeatsCountUpAndTheLogKeepsFifty() {
        var entries = emptyList<ProblemEntry>()
        entries = withProblem(entries, ProblemArea.GOOGLE_TRANSLATE, "a: HTTP 429", time)
        entries = withProblem(entries, ProblemArea.GOOGLE_TRANSLATE, "a: HTTP 429", time + 5)
        assertEquals(listOf(ProblemEntry(time + 5, ProblemArea.GOOGLE_TRANSLATE, "a: HTTP 429", 2)), entries)
        repeat(60) { entries = withProblem(entries, ProblemArea.CAPTIONS, "page NO_TRACKS; download $it", time + it) }
        assertEquals(MAX_PROBLEM_ENTRIES, entries.size)
        assertEquals("page NO_TRACKS; download 59", entries.last().code)
    }

    @Test
    fun aCodeKeepsOnlyTechnicalCharacters() {
        assertEquals("caf_ _b_x_/b_ _=", problemCode("café <b>x</b> <="))
        assertEquals(160, problemCode("x".repeat(500)).length)
        assertFalse(problemCode("line\nbreak\ttab").contains('\n'))
    }

    @Test
    fun theSavedLogReadsBackAndSkipsDamagedLines() {
        val entries =
            listOf(
                ProblemEntry(time, ProblemArea.APP_UPDATE, "OFFLINE"),
                ProblemEntry(time + 1, ProblemArea.GOOGLE_TRANSLATE, "translate.googleapis.com/translate_a/t: HTTP 429", 3),
            )
        val saved = encodeProblems(entries) + "garbage\n1\tNOPE\t1\tx\n"
        assertEquals(entries, decodeProblems(saved))
    }

    @Test
    fun theReportListsTheAppAndRecentProblemsOnly() {
        val report =
            problemReport(
                "1.9.0",
                "16 (API 36)",
                "Google Pixel 8",
                listOf(ProblemEntry(time, ProblemArea.GOOGLE_TRANSLATE, "a: HTTP 429", 2)),
            )
        assertTrue(report.startsWith("App: 1.9.0\nAndroid: 16 (API 36)\nPhone: Google Pixel 8"))
        assertTrue(report.endsWith("- 2027-01-15T08:00:00Z Google Translate: a: HTTP 429 (x2)"))
        assertTrue("No problems recorded recently." in problemReport("1", "2", "3", emptyList()))
    }

    @Test
    fun theIssueAddressFitsAndFillsTheBugReportForm() {
        val many = (1..50).map { ProblemEntry(time + it, ProblemArea.CAPTIONS, "number $it " + " x".repeat(70)) }
        val url = problemIssueUrl(problemReport("1.9.0", "16", "Pixel", many), "1.9.0", "Pixel 8")
        assertTrue(url.length <= MAX_PROBLEM_REPORT_URL_CHARS)
        assertTrue(
            url.startsWith(
                "https://github.com/hoangkien1703/dual-sub-replay/issues/new?template=bug_report.yml&app-version=1.9.0&device=Pixel%208&evidence=",
            ),
        )
        val evidence = URLDecoder.decode(url.substringAfter("&evidence="), "UTF-8")
        assertTrue(evidence.startsWith("App: 1.9.0"))
        // The newest problems stay; the oldest were left out to fit.
        assertTrue("number 50" in evidence)
        assertFalse("number 31 " in evidence)
    }
}
