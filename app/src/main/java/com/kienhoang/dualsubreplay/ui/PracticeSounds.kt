package com.kienhoang.dualsubreplay.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.CardPlace
import com.kienhoang.dualsubreplay.data.CardReadAloud
import com.kienhoang.dualsubreplay.data.LineAudioStore
import com.kienhoang.dualsubreplay.data.LinePictureStore
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.audioLineOf
import com.kienhoang.dualsubreplay.data.pictureLineOf
import com.kienhoang.dualsubreplay.data.readAloudTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Practice gets what a card sent to Anki has (owner request 2026-11-16): the word's voice, the line's
// own sound, the meaning and the translation read aloud, and the picture. Each sound is a speaker
// button, like the word card's, so nothing plays until tapped and no setting is needed; the picture
// follows Settings → Flashcards & Anki → Picture.

/** A speaker button; [description] says what it reads, for TalkBack. */
@Composable
internal fun SpeakerButton(
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
    }
}

/** The word in big letters with its speaker button, at the top of a Practice card. */
@Composable
internal fun PracticeWordTitle(
    word: SavedWord,
    onPronounce: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(word.word, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f, fill = false))
        SpeakerButton(stringResource(R.string.practice_pronounce_item, word.word), "practice_pronounce", onPronounce)
    }
}

/** [content] with a speaker button after it that reads [text] in [language]; no button when [text] is empty. */
@Composable
internal fun WithSpeaker(
    text: String,
    language: String,
    speak: (String, String) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        if (text.isNotEmpty()) {
            SpeakerButton(
                stringResource(R.string.practice_read_meaning),
                "practice_read_meaning",
            ) { speak(text, language) }
        }
    }
}

/** The meaning read aloud: the meaning in its sentence and the dictionary's, as the card reads them; empty when none is worth reading. */
internal fun practiceMeaningText(word: SavedWord): String =
    readAloudTexts(word, CardReadAloud(meaningInSentence = true, dictionaryMeaning = true)).meanings.joinToString(". ")

/** The sentence translation read aloud, as on the card; null when there is none. */
internal fun practiceTranslationText(word: SavedWord): String? =
    readAloudTexts(word, CardReadAloud(meaningInSentence = false, translation = true)).translation

/**
 * Plays the line's own sound when the app kept it for this saved word (it does while Add audio to
 * cards is on), else reads the word's line in its language.
 */
internal fun WordPronouncer.playLine(
    word: SavedWord,
    clips: LineAudioStore,
) {
    val own = if (word.translated) word.translatedSentence ?: word.sentence else word.sentence
    play(audioLineOf(word)?.let(clips::file), own, word.wordLanguage)
}

/**
 * The line's still (or the video's thumbnail), where Flashcards & Anki → Picture puts it on the card:
 * on the front it shows from the start, on the back after Show meaning ([revealed]).
 */
@Composable
internal fun PracticePicture(
    word: SavedWord,
    place: CardPlace,
    revealed: Boolean,
) {
    if (place == CardPlace.OFF || (place == CardPlace.BACK && !revealed)) return
    val context = LocalContext.current
    var picture by remember(word.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(word.id) {
        picture =
            withContext(Dispatchers.IO) {
                val store = LinePictureStore.of(context)
                val file = pictureLineOf(word)?.let(store::file) ?: word.videoId?.let(store::thumbnail)
                file?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
            }
    }
    picture?.let {
        Image(
            it,
            contentDescription = stringResource(R.string.practice_picture),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).testTag("practice_picture"),
        )
    }
}
