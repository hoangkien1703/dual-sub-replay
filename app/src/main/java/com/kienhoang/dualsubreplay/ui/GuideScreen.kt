package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.launch

private data class GuidePage(
    val title: String,
    val body: String,
    val imageRes: Int? = null,
    val imageContentDescription: String? = null,
    val imageAspectRatio: Float? = null,
    val imageCropFromTop: Boolean = false,
)

@Composable
fun GuideScreen(onFinish: () -> Unit) {
    val pages = remember { guidePages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    Surface(
        modifier = Modifier.fillMaxSize().testTag("guide_screen"),
        color = Color(0xFF061719),
        contentColor = Color(0xFFF3FAFA),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onFinish,
                    modifier = Modifier.testTag("guide_skip"),
                ) {
                    Text("Skip", color = Color(0xFFB7CED1))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("guide_pager"),
            ) { pageIndex ->
                GuidePageContent(
                    page = pages[pageIndex],
                    modifier = Modifier.fillMaxSize().testTag("guide_page_$pageIndex"),
                )
            }

            Spacer(Modifier.height(20.dp))
            GuidePageIndicator(
                pageCount = pages.size,
                currentPage = pagerState.currentPage,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(if (isLastPage) "guide_done" else "guide_next"),
            ) {
                Text(if (isLastPage) "Get started" else "Next")
            }
        }
    }
}

@Composable
private fun GuidePageContent(page: GuidePage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val imageRes = page.imageRes
        val aspectRatio = page.imageAspectRatio
        if (imageRes != null && aspectRatio != null) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = page.imageContentDescription,
                contentScale = ContentScale.Crop,
                alignment = if (page.imageCropFromTop) Alignment.TopCenter else Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF183034), RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(28.dp))
        }
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFF3FAFA),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB7CED1),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GuidePageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentPage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color(0xFF244044)
                        },
                    ),
            )
        }
    }
}

private fun guidePages(): List<GuidePage> = listOf(
    GuidePage(
        title = "Dual subtitles while you watch",
        body = "Play any YouTube video and see the original captions with a live translation underneath.",
        imageRes = R.drawable.guide_dual_subtitles,
        imageContentDescription = "Video playing with original and translated subtitles overlaid",
        imageAspectRatio = 16f / 10f,
    ),
    GuidePage(
        title = "Replay any line instantly",
        body = "Every spoken line is listed with its translation. Tap the play button on a line to jump the video back to that moment, and use the gear to change languages or text size.",
        imageRes = R.drawable.guide_caption_panel,
        imageContentDescription = "Caption panel listing subtitle lines with replay buttons",
        imageAspectRatio = 3f / 4f,
        imageCropFromTop = true,
    ),
    GuidePage(
        title = "Open any video",
        body = "Paste a YouTube link, share a video straight from the YouTube app, or just browse inside DualSub Replay.",
    ),
)
