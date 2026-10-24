package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiPendingQuestion
import com.kienhoang.dualsubreplay.assistant.aiHanziContext
import com.kienhoang.dualsubreplay.data.ChineseMorphology
import com.kienhoang.dualsubreplay.data.HSK_HIGHEST_BAND
import com.kienhoang.dualsubreplay.data.HanziDictionary
import com.kienhoang.dualsubreplay.data.HanziInfo
import com.kienhoang.dualsubreplay.data.HanziPartMeaning
import com.kienhoang.dualsubreplay.data.HanziStory
import com.kienhoang.dualsubreplay.data.HanziStrokes
import com.kienhoang.dualsubreplay.data.KanjiDictionary
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.hanziIn
import com.kienhoang.dualsubreplay.data.hanziPartMeaning
import com.kienhoang.dualsubreplay.data.myHanziStories
import com.kienhoang.dualsubreplay.data.pinyinInWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Make Me a Hanzi's strokes are drawn in a 512 × 512 box. */
private const val HANZI_BOX = 512f

/** Wide enough to cover any stroke's outline, which clips it, while the brush moves along its centre line. */
private const val HANZI_BRUSH_WIDTH = 72f
private const val SHOWN_HANZI_MEANINGS = 3

/** Chinese words get the Characters section (Settings → Study tools → Character details). */
internal fun isChineseWord(selection: LearningWordSelection): Boolean =
    LanguageAwareTokenizer.isChineseCode(selection.wordLanguage) && hanziIn(selection.token.text).isNotEmpty()

/** One character's row in the Characters section. */
internal data class HanziRow(
    val info: HanziInfo,
    val pinyin: String?,
    val keyword: String?,
    val hanViet: String?,
)

internal fun hanziRows(
    word: String,
    reading: String?,
    language: String,
    dictionary: HanziDictionary = HanziDictionary,
): List<HanziRow> {
    val inWord = pinyinInWord(word, reading)
    return hanziIn(word).mapNotNull { literal ->
        dictionary.info(literal)?.let { info ->
            HanziRow(
                info,
                inWord[literal] ?: info.pinyin.firstOrNull(),
                dictionary.keyword(literal, language),
                dictionary.hanViet(literal).firstOrNull(),
            )
        }
    }
}

/**
 * The word card's Characters section for a Chinese word: one row per character with its pinyin
 * and keyword; a tap opens that character's page ([HanziPage]) inside the card.
 */
@Composable
internal fun HanziSection(
    selection: LearningWordSelection,
    onOpen: (String) -> Unit,
) {
    val language = storyLanguage(selection)
    // Hán Việt is for Vietnamese learners, who also get the Vietnamese stories.
    val hanViet = language == "vi"
    val rows by produceState(emptyList<HanziRow>(), selection.token.text, language) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { hanziRows(selection.token.text, selection.token.reading, language) }.getOrDefault(emptyList())
            }
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("hanzi_section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.hanzi_section_title), style = MaterialTheme.typography.titleSmall)
        rows.forEach { row ->
            CharacterRowCard(
                literal = row.info.literal,
                hanViet = row.hanViet?.takeIf { hanViet },
                details =
                    listOfNotNull(
                        row.pinyin,
                        row.keyword,
                        pluralStringResource(R.plurals.kanji_strokes, row.info.strokes, row.info.strokes),
                    ).joinToString(" · "),
                testTag = "hanzi_row_${row.info.literal}",
            ) { onOpen(row.info.literal) }
        }
    }
}

/** Everything the page shows, read once on a background thread. */
private data class HanziPageData(
    val info: HanziInfo,
    val pinyin: List<String>,
    val keyword: String?,
    val hanViet: List<String>,
    val story: HanziStory?,
    val parts: List<HanziPartMeaning>,
    val strokes: HanziStrokes?,
    val radicalName: String?,
    val words: List<String>,
)

private fun hanziPageData(
    literal: String,
    selection: LearningWordSelection,
    language: String,
): HanziPageData? {
    val dictionary = HanziDictionary
    val info = dictionary.info(literal) ?: return null
    // The reading the word uses comes first (了 in 好了 is le, not liǎo).
    val inWord = pinyinInWord(selection.token.text, selection.token.reading)[literal]
    val radical = KanjiDictionary.radicalForm(info.radical)
    val radicalName =
        radical?.let {
            val name = if (language == "vi") "${it.vietnameseName} (${it.vietnameseMeaning})" else it.english
            "${info.radical} $name"
        } ?: info.radical.takeIf { it.isNotBlank() }
    val words =
        info.words.map { word ->
            val reading =
                ChineseMorphology
                    .analyze(word)
                    ?.singleOrNull()
                    ?.reading
                    ?.replace(" ", "")
            if (reading.isNullOrBlank()) word else "$word $reading"
        }
    return HanziPageData(
        info = info,
        pinyin = listOfNotNull(inWord).plus(info.pinyin).distinct(),
        keyword = dictionary.keyword(literal, language),
        hanViet = dictionary.hanViet(literal),
        story = dictionary.story(literal, language),
        parts = info.parts.map { hanziPartMeaning(it, language) },
        strokes = dictionary.strokes(literal),
        radicalName = radicalName,
        words = words,
    )
}

/**
 * One Chinese character's page inside the word card (owner request 2026-10-24): pinyin, Hán Việt
 * for Vietnamese learners, meaning, HSK level and the other form; stroke order with Play; parts
 * with the meaning part and the sound part marked; words with it; and how to remember it.
 */
@Composable
internal fun HanziPage(
    literal: String,
    selection: LearningWordSelection,
    onBack: () -> Unit,
    onAskedAi: () -> Unit,
) {
    val language = storyLanguage(selection)
    val hanViet = language == "vi"
    val data by produceState<HanziPageData?>(null, literal, language) {
        value = withContext(Dispatchers.IO) { runCatching { hanziPageData(literal, selection, language) }.getOrNull() }
    }
    Column(Modifier.fillMaxWidth().testTag("hanzi_page"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("hanzi_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.kanji_back, selection.token.text))
        }
        val page = data ?: return@Column
        HanziHeading(page, hanViet)
        page.strokes?.let { strokes ->
            CharacterWriting(literal, strokes.outlines.size) { modifier, progress, shown, highlightLast, guides ->
                HanziDrawing(strokes, modifier, progress, shown, highlightLast, guides)
            }
        }
        HanziParts(page)
        HanziWords(page.words)
        HanziMemory(literal, page, hanViet, selection, onAskedAi)
    }
}

@Composable
private fun HanziHeading(
    page: HanziPageData,
    hanViet: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(14.dp)) {
            val strokes = page.strokes
            if (strokes != null) {
                HanziDrawing(strokes, Modifier.size(88.dp).padding(6.dp), shown = strokes.outlines.size)
            } else {
                Text(page.info.literal, style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(10.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (hanViet && page.hanViet.isNotEmpty()) {
                Text(
                    page.hanViet.joinToString(", ") { it.uppercase(Locale.ROOT) },
                    color = colors.primary,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("hanzi_han_viet"),
                )
            }
            if (page.pinyin.isNotEmpty()) {
                Text(
                    page.pinyin.joinToString(", "),
                    color = if (hanViet) colors.onSurface else colors.primary,
                    fontWeight = FontWeight.Bold,
                    style = if (hanViet) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("hanzi_pinyin"),
                )
            }
            page.keyword?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            val english =
                page.info.meanings
                    .take(SHOWN_HANZI_MEANINGS)
                    .joinToString("; ")
            if (english.isNotBlank() && !english.equals(page.keyword, ignoreCase = true)) {
                Text(english, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            val facts =
                listOfNotNull(
                    pluralStringResource(R.plurals.kanji_strokes, page.info.strokes, page.info.strokes),
                    hskText(page.info.level),
                    page.radicalName?.let { stringResource(R.string.kanji_radical, it) },
                    otherFormText(page.info),
                )
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun hskText(level: Int): String? =
    when (level) {
        in 1 until HSK_HIGHEST_BAND -> stringResource(R.string.hanzi_hsk_level, level)
        HSK_HIGHEST_BAND -> stringResource(R.string.hanzi_hsk_advanced)
        else -> null
    }

/** "Traditional 葉" for a simplified character, "Simplified 叶" for a traditional form. */
@Composable
private fun otherFormText(info: HanziInfo): String? {
    if (info.otherForms.isEmpty()) return null
    val forms = info.otherForms.joinToString(" ")
    return if (info.traditional) stringResource(R.string.hanzi_simplified, forms) else stringResource(R.string.hanzi_traditional, forms)
}

/**
 * Make Me a Hanzi's strokes drawn in [modifier]'s box as brush shapes. While [progress] runs, the
 * next stroke is painted along its centre line, inside its outline, the way a brush moves.
 */
@Composable
private fun HanziDrawing(
    strokes: HanziStrokes,
    modifier: Modifier,
    progress: Float? = null,
    shown: Int = strokes.outlines.size,
    highlightLast: Boolean = false,
    guides: Boolean = false,
) {
    val outlines = remember(strokes) { strokes.outlines.map { PathParser().parsePathString(it).toPath() } }
    val medians =
        remember(strokes) {
            strokes.medians.map { points ->
                Path().apply {
                    points.forEachIndexed { index, (x, y) ->
                        if (index == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                    }
                }
            }
        }
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val unit = size.minDimension / HANZI_BOX
        if (guides) drawGuides(guide)
        scale(unit, unit, pivot = Offset.Zero) {
            if (progress != null) {
                // The whole character in faint outline, as in a copybook, under the strokes drawn so far.
                outlines.forEach { drawPath(it, guide) }
                val whole = progress.toInt().coerceAtMost(outlines.size)
                outlines.take(whole).forEach { drawPath(it, ink) }
                val fraction = progress - whole
                if (whole < outlines.size && fraction > 0f) {
                    clipPath(outlines[whole]) {
                        drawPath(
                            partOf(medians[whole], fraction),
                            accent,
                            style = Stroke(width = HANZI_BRUSH_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            } else {
                outlines.take(shown).forEachIndexed { index, path ->
                    drawPath(path, if (highlightLast && index == shown - 1) accent else ink)
                }
            }
        }
    }
}

@Composable
private fun HanziParts(page: HanziPageData) {
    if (page.parts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.kanji_parts), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            page.parts.forEach { part ->
                PartChip(
                    shape = part.part.shape,
                    name = listOfNotNull(part.hanViet, part.meaning).joinToString(" · "),
                    tags =
                        listOfNotNull(
                            stringResource(R.string.kanji_part_radical).takeIf { part.part.isRadical },
                            stringResource(R.string.hanzi_part_meaning).takeIf { part.part.givesMeaning },
                            part.sound?.let { stringResource(R.string.kanji_part_sound, it) }
                                ?: stringResource(R.string.kanji_part_sound_plain).takeIf { part.part.givesSound },
                        ),
                    testTag = "hanzi_part_${part.part.shape}",
                )
            }
        }
    }
}

@Composable
private fun HanziWords(words: List<String>) {
    if (words.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.hanzi_words), style = MaterialTheme.typography.titleSmall)
        Text(words.joinToString("   "), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("hanzi_words"))
    }
}

/** How to remember: the bundled story, the learner's own story, and a new one from the assistant. */
@Composable
private fun HanziMemory(
    literal: String,
    page: HanziPageData,
    hanViet: Boolean,
    selection: LearningWordSelection,
    onAskedAi: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.kanji_how_to_remember), style = MaterialTheme.typography.titleSmall)
        page.story?.let { story ->
            val label =
                story.form?.let { stringResource(R.string.hanzi_story_of_simplified, it) }
                    ?: stringResource(R.string.kanji_memory_story)
            MemoryStoryCard(label, story.story, "hanzi_story")
        }
        val context = LocalContext.current
        MyStoryField(literal, remember(context) { myHanziStories(context.filesDir) }, "hanzi_my_story")
        AiHanziStoryButton(literal, page, hanViet, selection, onAskedAi)
    }
}

/** Asks the assistant for another story; shown only while the assistant is on. */
@Composable
private fun AiHanziStoryButton(
    literal: String,
    page: HanziPageData,
    hanViet: Boolean,
    selection: LearningWordSelection,
    onAsked: () -> Unit,
) {
    val host = LocalAiAssistant.current ?: return
    val aiState by host.controller.state.collectAsStateWithLifecycle()
    if (!aiState.settings.enabled) return
    val question = stringResource(R.string.hanzi_ai_story_question, literal)
    OutlinedButton(
        onClick = {
            val context =
                aiHanziContext(
                    hanzi = literal,
                    word = selection.token.text,
                    meanings = page.info.meanings,
                    pinyin = page.pinyin,
                    hanViet = page.hanViet.takeIf { hanViet }.orEmpty(),
                    parts =
                        page.parts.map { part ->
                            listOfNotNull(part.part.shape, part.meaning, part.sound?.let { "(sound $it)" }).joinToString(" ")
                        },
                    bundledStory = page.story?.story,
                )
            host.controller.askFromApp(AiPendingQuestion(question, context, literal))
            onAsked()
        },
        modifier = Modifier.testTag("hanzi_ask_ai"),
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.kanji_ai_story_button))
    }
}
