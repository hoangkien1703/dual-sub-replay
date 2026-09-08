package com.kienhoang.dualsubreplay.ui

import android.content.Context
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit benchmark-only evaluation; never part of offline automated CI tests. */
internal suspend fun evaluateTranslation(context: Context): String {
    val translator = OnDeviceTranslator()
    val samples =
        listOf(
            Triple("en", "vi", listOf("I gave up", "because it was raining.")),
            Triple("en", "vi", listOf("Could you give me", "a hand with this box?")),
            Triple("en", "vi", listOf("She has been working", "here for five years.")),
            Triple("en", "vi", listOf("If I had known,", "I would have called you.")),
            Triple("en", "vi", listOf("Could you give me a hand with this box,", "because it is too heavy for me to carry alone?")),
            Triple("vi", "en", listOf("Tôi đã sống ở đây", "được năm năm rồi.")),
            Triple("ja", "vi", listOf("雨が降っていたので", "出かけるのをやめました。")),
        )
    val results = JSONArray()
    samples.forEach { (source, target, fragments) ->
        val row = JSONObject().put("source", source).put("target", target).put("fragments", JSONArray(fragments))
        try {
            row.put("before", JSONArray(fragments.map { translator.translateSingle(source, target, it) }))
            val sentence = fragments.joinToString(if (source == "ja") "" else " ")
            row.put("wholeSentence", translator.translateSingle(source, target, sentence))
            val segments =
                com.kienhoang.dualsubreplay.data.SubtitleMerger.merge(
                    listOf(
                        com.kienhoang.dualsubreplay.data
                            .RawCaptionCue(0, 6000, sentence),
                    ),
                )
            val short = captionDisplaySegments(segments, CaptionFormat.SHORT_PHRASES, true)
            row.put("shortInputs", JSONArray(short.map { it.originalText }))
            row.put("shortTranslations", JSONArray(short.map { translator.translateSingle(source, target, it.originalText) }))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            row.put("error", error.message)
        }
        results.put(row)
    }
    val output = File(context.getExternalFilesDir(null), "translation-evaluation.json")
    withContext(Dispatchers.IO) { output.writeText(results.toString(2)) }
    return "Translation evaluation saved: ${output.name}"
}
