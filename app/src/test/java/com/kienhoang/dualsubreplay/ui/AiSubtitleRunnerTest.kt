package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.AiPartAnswer
import com.kienhoang.dualsubreplay.assistant.AiSubtitleException
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import com.kienhoang.dualsubreplay.data.AiSubtitlePart
import com.kienhoang.dualsubreplay.data.AiSubtitleStore
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiSubtitleRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val videoId = "yjsAuMiLc_A"

    private fun line(startMs: Long) =
        AiPartAnswer.Written(AiSubtitlePart("es", listOf(RawCaptionCue(startMs + 1_000, startMs + 2_000, "Hola"))))

    @Test
    fun theFirstPartLoadsAtOnceAndTheRestLoadsWhenAllAreIn() =
        runBlocking {
            val store = AiSubtitleStore(folder.root)
            val asked = mutableListOf<Long>()
            val ready = mutableListOf<String>()
            val statuses = mutableListOf<AiSubtitleStatus?>()
            val runner =
                AiSubtitleRunner(
                    scope = this,
                    store = store,
                    askPart = { _, start, _ ->
                        asked += start
                        if (start >= 600_000) AiPartAnswer.PastTheEnd else line(start)
                    },
                    durationOf = { null },
                    onStatus = { statuses += it },
                    onReady = { ready += it },
                    io = Dispatchers.Unconfined,
                )
            // Playing at 6:10: the part from 5:00 comes first.
            runner.start(videoId, positionMs = 370_000)
            runner.join()
            assertEquals(listOf(300_000L, 600_000L, 0L), asked)
            assertEquals(listOf(videoId, videoId), ready)
            assertNull(statuses.last())
            assertTrue(statuses.first()!!.working)
            val saved = store.read(videoId)!!
            assertTrue(saved.complete)
            assertEquals(600_000L, saved.endMs)
            assertEquals(2, saved.cues().size)
        }

    @Test
    fun aProblemBeforeAnythingShowsIsShownOnTheCard() =
        runBlocking {
            val statuses = mutableListOf<AiSubtitleStatus?>()
            val runner =
                AiSubtitleRunner(
                    scope = this,
                    store = AiSubtitleStore(folder.root),
                    askPart = { _, _, _ -> throw AiSubtitleException(AiSubtitleProblem.VIDEO_UNAVAILABLE) },
                    durationOf = { 170_000 },
                    onStatus = { statuses += it },
                    onReady = {},
                    io = Dispatchers.Unconfined,
                )
            runner.start(videoId, 0)
            runner.join()
            assertEquals(AiSubtitleProblem.VIDEO_UNAVAILABLE, statuses.last()!!.problem)
        }

    @Test
    fun aLoadedUnfinishedTrackCarriesOnAndALaterLimitIsAskedAgain() =
        runBlocking {
            val store = AiSubtitleStore(folder.root)
            val first =
                AiSubtitleRunner(
                    scope = this,
                    store = store,
                    askPart = { _, start, _ -> if (start == 0L) line(0) else throw AiSubtitleException(AiSubtitleProblem.NETWORK) },
                    durationOf = { 400_000 },
                    onStatus = {},
                    onReady = {},
                    retryWaitsMs = emptyList(),
                    io = Dispatchers.Unconfined,
                )
            first.start(videoId, 0)
            first.join()
            assertEquals(false, store.read(videoId)!!.complete)

            var calls = 0
            val ready = mutableListOf<String>()
            val second =
                AiSubtitleRunner(
                    scope = this,
                    store = store,
                    askPart = { _, start, _ ->
                        calls++
                        if (calls == 1) throw AiSubtitleException(AiSubtitleProblem.LIMIT) else line(start)
                    },
                    durationOf = { 400_000 },
                    onStatus = {},
                    onReady = { ready += it },
                    retryWaitsMs = listOf(0L),
                    io = Dispatchers.Unconfined,
                )
            second.resumeIfUnfinished(videoId, 0)
            second.join()
            assertEquals(2, calls)
            assertTrue(store.read(videoId)!!.complete)
            assertEquals(listOf(videoId), ready)

            // Complete now: opening it again asks nothing.
            second.resumeIfUnfinished(videoId, 0)
            second.join()
            assertEquals(2, calls)
        }
}
