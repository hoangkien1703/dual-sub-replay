package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.NativeChannel
import com.kienhoang.dualsubreplay.data.NativeChannelPage
import com.kienhoang.dualsubreplay.data.NativeComment
import com.kienhoang.dualsubreplay.data.NativeCommentPage
import com.kienhoang.dualsubreplay.data.NativePage
import com.kienhoang.dualsubreplay.data.NativePlaylist
import com.kienhoang.dualsubreplay.data.NativePlaylistPage
import com.kienhoang.dualsubreplay.data.NativeVideo
import com.kienhoang.dualsubreplay.data.NativeWatchDetails
import com.kienhoang.dualsubreplay.data.NativeYouTubeSource
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val CHANNEL_ID = "UCabcdefghijklmnopqrstuv"

class NativeModeUiTest {
    @get:Rule val compose = createComposeRule()

    private val application: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before
    fun startWithAnEmptyLibrary() {
        application
            .getSharedPreferences("native_mode_library", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun searchWatchCommentsAndLibraryWorkBesideTheBetaNotice() {
        val viewModel = NativeModeViewModel(application, FakeNativeSource())
        var activeVideoId by mutableStateOf<String?>(null)
        var switchedBack = 0
        compose.setContent {
            DualSubTheme {
                val host =
                    NativeModeHost(
                        activeVideoId = activeVideoId,
                        playing = true,
                        play = { video, _ ->
                            viewModel.openVideo(video)
                            activeVideoId = video.videoId
                        },
                        pause = {},
                        resume = {},
                        stop = { activeVideoId = null },
                        switchBack = { switchedBack++ },
                        subtitles = { modifier -> Text("Subtitle timeline", modifier.testTag("fake_subtitles")) },
                    )
                Box(Modifier.fillMaxSize()) {
                    NativeModeShell(viewModel, host, videoHeight = 200.dp, showWatch = true)
                }
            }
        }

        compose.onNodeWithTag("native_beta_notice").assertIsDisplayed()
        compose.onNodeWithText("Search for something to watch").assertIsDisplayed()
        saveUiEvidence("native-mode-home")

        compose.onNodeWithTag("native_search_field").performTextInput("spanish lessons")
        compose.onNodeWithTag("native_search_field").performImeAction()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native_video_aaaaaaaaaaa").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("10K subscribers").assertIsDisplayed()
        saveUiEvidence("native-mode-search")

        compose.onNodeWithTag("native_video_aaaaaaaaaaa").performClick()
        compose.onNodeWithTag("native_watch").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1K views · Oct 1, 2026").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fake_subtitles").assertIsDisplayed()
        saveUiEvidence("native-mode-watch")

        compose.onNodeWithTag("native_watch_tab_comments").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Great lesson").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        saveUiEvidence("native-mode-comments")

        compose.onNodeWithTag("native_watch_tab_up_next").performClick()
        compose.onNodeWithText("Next lesson").assertIsDisplayed()

        compose.runOnIdle { assertTrue(viewModel.back()) }
        compose.onNodeWithTag("native_search_results").assertIsDisplayed()
        compose.onNodeWithTag("native_mini_player").assertIsDisplayed()

        compose.onNodeWithTag("native_tab_library").performClick()
        compose.onNodeWithTag("native_library").assertIsDisplayed()
        compose.onAllNodesWithText("Spanish lesson")[0].assertIsDisplayed()
        saveUiEvidence("native-mode-library")

        compose.onNodeWithTag("native_tab_home").performClick()
        compose.onNodeWithText("Continue watching").assertIsDisplayed()
        compose.onNodeWithTag("native_switch_back").performClick()
        compose.runOnIdle { assertEquals(1, switchedBack) }
    }

    @Test
    fun leftBarShowsNativeModeAsBetaAndSwitchesIt() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            DualSubTheme {
                AppNavigation(
                    onPractice = {},
                    onSettings = {},
                    nativeModeEnabled = enabled,
                    onNativeModeChange = { enabled = it },
                ) { menu -> menu() }
            }
        }

        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose
            .onNodeWithTag("native_mode_drawer_item")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOff()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        saveUiEvidence("native-mode-drawer")

        compose.onNodeWithTag("native_mode_drawer_item").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(enabled) }

        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithTag("native_mode_drawer_item").performScrollTo().assertIsOn()
    }
}

private class FakeNativeSource : NativeYouTubeSource {
    private val video = NativeVideo("aaaaaaaaaaa", "Spanish lesson", "Teacher", CHANNEL_ID, "12:34", "1K views · 1 day ago")
    private val teacher = NativeChannel(CHANNEL_ID, "Teacher", detailText = "10K subscribers")

    override suspend fun search(query: String) = NativePage(listOf(video, teacher))

    override suspend fun moreSearch(continuation: String) = NativePage(emptyList())

    override suspend fun moreBrowse(continuation: String) = NativePage(emptyList())

    override suspend fun watch(videoId: String) =
        NativeWatchDetails(
            videoId = videoId,
            title = "Spanish lesson",
            channel = teacher,
            viewsText = "1K views",
            dateText = "Oct 1, 2026",
            description = "Lesson notes",
            related = NativePage(listOf(NativeVideo("bbbbbbbbbbb", "Next lesson", "Teacher"))),
            commentsContinuation = "comments",
        )

    override suspend fun moreRelated(continuation: String) = NativePage(emptyList())

    override suspend fun comments(continuation: String) =
        NativeCommentPage(
            countText = "1 Comment",
            comments = listOf(NativeComment(id = "c1", author = "@teacher", text = "Great lesson", likesText = "12", pinned = true)),
        )

    override suspend fun channel(channelId: String) = NativeChannelPage(teacher, "Channel notes", NativePage(listOf(video)))

    override suspend fun playlist(playlistId: String) = NativePlaylistPage(NativePlaylist(playlistId, "Lessons"), NativePage(listOf(video)))

    override suspend fun latestUploads(channelId: String) = emptyList<Pair<NativeVideo, Long>>()
}
