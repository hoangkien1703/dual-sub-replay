package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SavedWord
import java.util.Locale

/** A video words were saved from, by the title kept with them (idea from mrAlishah's Triple Sub Replay fork). */
internal data class PracticeVideo(
    val videoId: String,
    val title: String,
    val count: Int,
)

/** The videos [words] came from that have a title, A to Z; words saved before titles were kept have none. */
internal fun practiceVideos(words: List<SavedWord>): List<PracticeVideo> =
    words
        .filter { it.videoId != null }
        .groupBy { checkNotNull(it.videoId) }
        .mapNotNull { (videoId, saved) ->
            saved.lastOrNull { it.sourceTitle != null }?.sourceTitle?.let { PracticeVideo(videoId, it, saved.size) }
        }.sortedBy { it.title.lowercase(Locale.ROOT) }

/** The Practice search: the word, its meaning, the video's title or the learner's notes. */
internal fun matchesPracticeSearch(
    word: SavedWord,
    query: String,
): Boolean {
    val text = query.trim()
    return text.isEmpty() ||
        word.word.contains(text, true) ||
        word.meaning.contains(text, true) ||
        word.sourceTitle?.contains(text, true) == true ||
        word.notes.contains(text, true)
}

/** Practice → "Video: All videos ▾": shows (and practises) only the words of one video. */
@Composable
internal fun VideoFilterChip(
    videos: List<PracticeVideo>,
    selected: String?,
    onPick: (String?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val allVideos = stringResource(R.string.practice_video_filter_all)
    val label = videos.firstOrNull { it.videoId == selected }?.title ?: allVideos
    Box {
        AssistChip(
            onClick = { menu = true },
            label = { Text(stringResource(R.string.practice_video_filter, label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.practice_video_filter_choose),
                )
            },
            modifier = Modifier.testTag("video_filter"),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(allVideos) },
                onClick = {
                    menu = false
                    onPick(null)
                },
                modifier = Modifier.testTag("video_filter_all"),
            )
            HorizontalDivider()
            videos.forEach { video ->
                DropdownMenuItem(
                    text = { Text("${video.title} (${video.count})", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        menu = false
                        onPick(video.videoId)
                    },
                    modifier = Modifier.testTag("video_filter_${video.videoId}"),
                )
            }
        }
    }
}
