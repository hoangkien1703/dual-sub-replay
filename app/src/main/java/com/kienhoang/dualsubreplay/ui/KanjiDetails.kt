package com.kienhoang.dualsubreplay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiPendingQuestion
import com.kienhoang.dualsubreplay.assistant.aiKanjiContext
import com.kienhoang.dualsubreplay.data.KanjiDictionary
import com.kienhoang.dualsubreplay.data.KanjiInfo
import com.kienhoang.dualsubreplay.data.KanjiPartMeaning
import com.kienhoang.dualsubreplay.data.KanjiStory
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.MyKanjiStories
import com.kienhoang.dualsubreplay.data.kanjiIn
import com.kienhoang.dualsubreplay.data.kanjiPartMeaning
import com.kienhoang.dualsubreplay.data.kanjiStoryLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** KanjiVG draws every kanji in a 109 × 109 box. */
private const val KANJIVG_BOX = 109f
private const val KANJIVG_STROKE_WIDTH = 3.2f
private const val STROKE_MS_PER_STROKE = 450
private const val MY_STORY_SAVE_DELAY_MS = 600L
private const val SHOWN_MEANINGS = 4

/** Japanese words get the Kanji section (Settings → Study tools → Kanji details). */
internal fun isJapaneseWord(selection: LearningWordSelection): Boolean =
    LanguageAwareTokenizer.isJapanese(selection.token.text, selection.wordLanguage) && kanjiIn(selection.token.text).isNotEmpty()

/** One kanji's row in the Kanji section: the kanji, its Hán Việt for Vietnamese, its keyword. */
internal data class KanjiRow(
    val info: KanjiInfo,
    val keyword: String?,
)

internal fun kanjiRows(
    word: String,
    language: String,
    dictionary: KanjiDictionary = KanjiDictionary,
): List<KanjiRow> =
    kanjiIn(word).mapNotNull { literal ->
        dictionary.info(literal)?.let { KanjiRow(it, dictionary.keyword(literal, language)) }
    }

/** Kun readings as KANJIDIC2 writes them (やす.む, -あ.け) shown as やす(む), あ(け). */
internal fun kunReadingText(reading: String): String {
    val plain = reading.trim('-')
    val stem = plain.substringBefore('.')
    val ending = plain.substringAfter('.', "")
    return if (ending.isEmpty()) stem else "$stem($ending)"
}

@Composable
private fun storyLanguage(selection: LearningWordSelection): String =
    kanjiStoryLanguage(selection.meaningLanguage, currentInterfaceLocale().language)

/**
 * The word card's Kanji section: one row per kanji in the word; a tap opens that kanji's page
 * ([KanjiPage]) inside the card. Nothing shows for kanji the bundled data does not have.
 */
@Composable
internal fun KanjiSection(
    selection: LearningWordSelection,
    onOpen: (String) -> Unit,
) {
    val language = storyLanguage(selection)
    // Hán Việt is for Vietnamese learners, who also get the Vietnamese stories.
    val hanViet = language == "vi"
    val rows by produceState(emptyList<KanjiRow>(), selection.token.text, language) {
        value = withContext(Dispatchers.IO) { runCatching { kanjiRows(selection.token.text, language) }.getOrDefault(emptyList()) }
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("kanji_section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.kanji_section_title), style = MaterialTheme.typography.titleSmall)
        rows.forEach { row -> KanjiRowCard(row, hanViet) { onOpen(row.info.literal) } }
    }
}

@Composable
private fun KanjiRowCard(
    row: KanjiRow,
    hanViet: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHighest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("kanji_row_${row.info.literal}"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.info.literal, fontSize = 32.sp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                if (hanViet) {
                    row.info.hanViet.firstOrNull()?.let {
                        Text(
                            it.uppercase(Locale.ROOT),
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                val strokes = pluralStringResource(R.plurals.kanji_strokes, row.info.strokes, row.info.strokes)
                Text(
                    listOfNotNull(row.keyword, strokes).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

/** Everything the page shows, read once on a background thread. */
private data class KanjiPageData(
    val info: KanjiInfo,
    val keyword: String?,
    val story: KanjiStory?,
    val parts: List<KanjiPartMeaning>,
    val soundReadings: Map<String, String>,
    val strokes: List<String>,
    val radicalName: String?,
)

private fun kanjiPageData(
    literal: String,
    language: String,
): KanjiPageData? {
    val dictionary = KanjiDictionary
    val info = dictionary.info(literal) ?: return null
    val parts = info.parts.map { kanjiPartMeaning(it, language) }
    val sound =
        info.parts
            .filter { it.givesSound }
            .mapNotNull { part ->
                dictionary
                    .info(part.shape)
                    ?.onReadings
                    ?.firstOrNull()
                    ?.let { part.shape to it }
            }.toMap()
    val radical = dictionary.radicals[info.radical]
    val radicalName =
        radical?.let {
            val name = if (language == "vi") "${it.vietnameseName} (${it.vietnameseMeaning})" else it.english
            "${it.forms.first()} $name"
        }
    return KanjiPageData(
        info,
        dictionary.keyword(literal, language),
        dictionary.story(literal, language),
        parts,
        sound,
        dictionary.strokes(literal),
        radicalName,
    )
}

/**
 * One kanji's page inside the word card (owner request 2026-10-24): meaning and Hán Việt,
 * readings, stroke order with Play, parts with the radical and sound part marked, and how to
 * remember it: the bundled story, the learner's own story, and another story from the assistant.
 */
@Composable
internal fun KanjiPage(
    literal: String,
    selection: LearningWordSelection,
    onBack: () -> Unit,
    onAskedAi: () -> Unit,
) {
    val language = storyLanguage(selection)
    // Hán Việt is for Vietnamese learners, who also get the Vietnamese stories.
    val hanViet = language == "vi"
    val data by produceState<KanjiPageData?>(null, literal, language) {
        value = withContext(Dispatchers.IO) { runCatching { kanjiPageData(literal, language) }.getOrNull() }
    }
    Column(Modifier.fillMaxWidth().testTag("kanji_page"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("kanji_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.kanji_back, selection.token.text))
        }
        val page = data ?: return@Column
        KanjiHeading(page, hanViet)
        KanjiReadings(page.info)
        KanjiWriting(literal, page.strokes)
        KanjiParts(page)
        KanjiMemory(literal, page, hanViet, selection, onAskedAi)
    }
}

@Composable
private fun KanjiHeading(
    page: KanjiPageData,
    hanViet: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(14.dp)) {
            KanjiDrawing(page.strokes, Modifier.size(88.dp).padding(6.dp), shown = page.strokes.size)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (hanViet) {
                page.info.hanViet.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it.joinToString(", ") { reading -> reading.uppercase(Locale.ROOT) },
                        color = colors.primary,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("kanji_han_viet"),
                    )
                }
            }
            page.keyword?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            val english =
                page.info.meanings
                    .take(SHOWN_MEANINGS)
                    .joinToString(", ")
            if (english.isNotBlank() && !english.equals(page.keyword, ignoreCase = true)) {
                Text(english, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            val facts =
                listOfNotNull(
                    pluralStringResource(R.plurals.kanji_strokes, page.info.strokes, page.info.strokes),
                    gradeText(page.info.grade),
                    page.radicalName?.let { stringResource(R.string.kanji_radical, it) },
                )
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun gradeText(grade: Int): String =
    when (grade) {
        in 1..6 -> stringResource(R.string.kanji_grade_school, grade)
        8 -> stringResource(R.string.kanji_grade_secondary)
        else -> stringResource(R.string.kanji_grade_names)
    }

@Composable
private fun KanjiReadings(info: KanjiInfo) {
    if (info.onReadings.isEmpty() && info.kunReadings.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (info.onReadings.isNotEmpty()) ReadingLine(stringResource(R.string.kanji_on), info.onReadings.joinToString("、"))
        if (info.kunReadings.isNotEmpty()) {
            ReadingLine(
                stringResource(R.string.kanji_kun),
                info.kunReadings
                    .map(::kunReadingText)
                    .distinct()
                    .joinToString("、"),
            )
        }
    }
}

@Composable
private fun ReadingLine(
    label: String,
    readings: String,
) {
    Row {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
        Text(readings, style = MaterialTheme.typography.bodyLarge)
    }
}

/** How to write: the kanji drawn stroke by stroke when Play is tapped, then each stroke numbered. */
@Composable
private fun KanjiWriting(
    literal: String,
    strokes: List<String>,
) {
    if (strokes.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val progress = remember(literal) { Animatable(strokes.size.toFloat()) }
    val scope = rememberCoroutineScope()
    var plays by remember(literal) { mutableIntStateOf(0) }
    LaunchedEffect(plays) {
        if (plays == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(strokes.size.toFloat(), tween(strokes.size * STROKE_MS_PER_STROKE, easing = LinearEasing))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.kanji_how_to_write), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { plays += 1 } }, modifier = Modifier.testTag("kanji_play_strokes")) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.kanji_play))
            }
        }
        val description =
            stringResource(
                R.string.kanji_stroke_order_description,
                literal,
                pluralStringResource(R.plurals.kanji_strokes, strokes.size, strokes.size),
            )
        Surface(
            color = colors.surfaceContainerHighest,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = description },
        ) {
            KanjiDrawing(strokes, Modifier.size(150.dp).padding(8.dp), progress = progress.value, guides = true)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            strokes.indices.forEach { index -> StrokeFrame(strokes, index) }
        }
    }
}

@Composable
private fun StrokeFrame(
    strokes: List<String>,
    index: Int,
    size: Dp = 46.dp,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
        Box {
            KanjiDrawing(strokes, Modifier.size(size).padding(3.dp), shown = index + 1, highlightLast = true, guides = true)
            Text(
                (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

/**
 * KanjiVG's strokes drawn in [modifier]'s box. [progress] counts strokes, its fraction drawing part
 * of the next one; [shown] draws whole strokes only, the last in the accent color with [highlightLast].
 */
@Composable
private fun KanjiDrawing(
    strokes: List<String>,
    modifier: Modifier,
    progress: Float? = null,
    shown: Int = strokes.size,
    highlightLast: Boolean = false,
    guides: Boolean = false,
) {
    val paths = remember(strokes) { strokes.map { PathParser().parsePathString(it).toPath() } }
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val unit = size.minDimension / KANJIVG_BOX
        if (guides) drawGuides(guide)
        scale(unit, unit, pivot = Offset.Zero) {
            val style = Stroke(width = KANJIVG_STROKE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (progress != null) {
                val whole = progress.toInt().coerceAtMost(paths.size)
                paths.take(whole).forEach { drawPath(it, ink, style = style) }
                val fraction = progress - whole
                if (whole < paths.size && fraction > 0f) drawPath(partOf(paths[whole], fraction), accent, style = style)
            } else {
                paths.take(shown).forEachIndexed { index, path ->
                    drawPath(path, if (highlightLast && index == shown - 1) accent else ink, style = style)
                }
            }
        }
    }
}

private fun DrawScope.drawGuides(color: Color) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), pathEffect = dash)
    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), pathEffect = dash)
}

private fun partOf(
    path: Path,
    fraction: Float,
): Path {
    val measure = PathMeasure()
    measure.setPath(path, false)
    return Path().also { measure.getSegment(0f, measure.length * fraction, it, true) }
}

@Composable
private fun KanjiParts(page: KanjiPageData) {
    if (page.parts.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.kanji_parts), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            page.parts.forEach { part ->
                Surface(
                    color = colors.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("kanji_part_${part.part.shape}"),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(part.part.shape, fontSize = 22.sp)
                        Column(Modifier.padding(start = 6.dp)) {
                            val name = listOfNotNull(part.hanViet, part.meaning).joinToString(" · ")
                            if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.bodyMedium)
                            val tags =
                                listOfNotNull(
                                    stringResource(R.string.kanji_part_radical).takeIf { part.part.isRadical },
                                    page.soundReadings[part.part.shape]?.let { stringResource(R.string.kanji_part_sound, it) }
                                        ?: stringResource(R.string.kanji_part_sound_plain).takeIf { part.part.givesSound },
                                )
                            if (tags.isNotEmpty()) {
                                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How to remember: the bundled story, the learner's own story, and a new one from the assistant. */
@Composable
private fun KanjiMemory(
    literal: String,
    page: KanjiPageData,
    hanViet: Boolean,
    selection: LearningWordSelection,
    onAskedAi: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.kanji_how_to_remember), style = MaterialTheme.typography.titleSmall)
        page.story?.let { story ->
            Surface(
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("kanji_story"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.kanji_memory_story), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Text(story.story, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        MyStoryField(literal)
        AiKanjiStoryButton(literal, page, hanViet, selection, onAskedAi)
    }
}

@Composable
private fun MyStoryField(literal: String) {
    val context = LocalContext.current
    val stories = remember(context) { MyKanjiStories.inDirectory(context.filesDir) }
    var text by remember(literal) { mutableStateOf<String?>(null) }
    LaunchedEffect(literal) { text = withContext(Dispatchers.IO) { stories.get(literal).orEmpty() } }
    val current = text ?: return
    var edited by remember(literal) { mutableStateOf(false) }
    LaunchedEffect(current, edited) {
        if (!edited) return@LaunchedEffect
        delay(MY_STORY_SAVE_DELAY_MS)
        withContext(Dispatchers.IO) { stories.set(literal, current) }
    }
    OutlinedTextField(
        current,
        {
            text = it
            edited = true
        },
        label = { Text(stringResource(R.string.kanji_my_story)) },
        placeholder = { Text(stringResource(R.string.kanji_my_story_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("kanji_my_story"),
    )
}

/** Asks the assistant for another story; shown only while the assistant is on. */
@Composable
private fun AiKanjiStoryButton(
    literal: String,
    page: KanjiPageData,
    hanViet: Boolean,
    selection: LearningWordSelection,
    onAsked: () -> Unit,
) {
    val host = LocalAiAssistant.current ?: return
    val aiState by host.controller.state.collectAsStateWithLifecycle()
    if (!aiState.settings.enabled) return
    val question = stringResource(R.string.kanji_ai_story_question, literal)
    OutlinedButton(
        onClick = {
            val context =
                aiKanjiContext(
                    kanji = literal,
                    word = selection.token.text,
                    meanings = page.info.meanings,
                    onReadings = page.info.onReadings,
                    kunReadings = page.info.kunReadings.map(::kunReadingText),
                    hanViet =
                        page.info.hanViet
                            .takeIf { hanViet }
                            .orEmpty(),
                    parts = page.parts.map { part -> listOfNotNull(part.part.shape, part.meaning).joinToString(" ") },
                    bundledStory = page.story?.story,
                )
            host.controller.askFromApp(AiPendingQuestion(question, context, literal))
            onAsked()
        },
        modifier = Modifier.testTag("kanji_ask_ai"),
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.kanji_ai_story_button))
    }
}
