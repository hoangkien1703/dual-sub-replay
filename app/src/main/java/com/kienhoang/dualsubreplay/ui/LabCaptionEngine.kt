package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kienhoang.dualsubreplay.data.SubtitleWord
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import org.json.JSONObject

/** The preview-16 engine is the only authority for spoken-word selection. */
internal fun labEngineConfigurationScript(
    enabled: Boolean,
    language: String,
    engine: String,
): String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return;
      window.__dualSubLabOptions = { enabled: $enabled, language: ${JSONObject.quote(language)} };
      $engine
    })();
    """.trimIndent()

internal fun parseLabCaptionEvent(
    raw: String?,
    pageUrl: String?,
    nowEpochMs: Long,
): LiveCaptionSample? {
    if (raw == null || raw.length > 32_768 || pageUrl == null || !isYouTubeWebUrl(pageUrl)) return null
    return runCatching {
        val json = JSONObject(raw)
        val url = json.optString("url")
        val videoId = YouTubeUrlParser.extractVideoId(pageUrl) ?: return@runCatching null
        val second = json.optDouble("currentSecond", Double.NaN)
        val age = nowEpochMs - json.optLong("sampledAtEpochMs", 0)
        val text = json.optString("text")
        val index = json.optInt("activeWordIndex", -1)
        val revision = json.optLong("revision", -1)
        val language = json.optString("languageCode")
        if (json.optString("type") != "caption" || !isYouTubeWebUrl(url) || url != pageUrl ||
            json.optString("videoId") != videoId || !second.isFinite() || second < 0 ||
            age !in 0..250 || text.length > MAX_LIVE_CAPTION_TEXT_LENGTH || revision < 0 ||
            language.length !in 2..35 || index < -1 || index >= karaokeTokens(text).size
        ) {
            return@runCatching null
        }
        LiveCaptionSample(
            text = text,
            revision = revision,
            mediaTimeMs = (second * 1000).toLong(),
            present = text.isNotBlank(),
            videoId = videoId,
            languageCode = language,
            activeWordIndex = index,
        )
    }.getOrNull()
}

internal fun installLabCaptionBridge(
    webView: WebView,
    onCaption: (LiveCaptionSample) -> Unit,
) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
    WebViewCompat.addWebMessageListener(
        webView,
        "DualSubCaptionBridge",
        setOf("https://youtube.com", "https://*.youtube.com"),
    ) { view, message, sourceOrigin, isMainFrame, _ ->
        if (isMainFrame && isYouTubeWebUrl(sourceOrigin.toString())) {
            parseLabCaptionEvent(message.data, view.url, System.currentTimeMillis())?.let(onCaption)
        }
    }
}

/** Character mapping only: these placeholders are never used to select a word by time. */
internal fun labCaptionWords(text: String): List<SubtitleWord> =
    Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")
        .findAll(text)
        .map { SubtitleWord(it.value, 0, 0) }
        .toList()

internal fun loadLabCaptionEngine(context: Context): String =
    context.assets
        .open("youtube-caption-engine.js")
        .bufferedReader()
        .use { it.readText() }

internal fun captionEngineLanguage(state: DualSubUiState): String =
    state.resolvedSourceLanguage ?: state.sourcePreference.takeUnless { it == "auto" } ?: "en"
