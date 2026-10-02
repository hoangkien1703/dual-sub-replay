package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import java.util.Locale

/** One interface language, named in its own language so people can find theirs in any app language. */
internal data class AppLanguageOption(
    val tag: String,
    val nativeName: String,
)

/**
 * The interface languages with a translated `values-*` folder. English (`values/`) is the default;
 * with no choice saved the app follows the device's language list.
 */
internal val APP_LANGUAGES =
    listOf(
        AppLanguageOption("en", "English"),
        AppLanguageOption("vi", "Tiếng Việt"),
        AppLanguageOption("es", "Español"),
        AppLanguageOption("pt-BR", "Português (Brasil)"),
        AppLanguageOption("ja", "日本語"),
        AppLanguageOption("ko", "한국어"),
        AppLanguageOption("zh-CN", "简体中文"),
        AppLanguageOption("id", "Bahasa Indonesia"),
    )

internal const val APP_LANGUAGE_PREFERENCE = "app_language"

/** Android still reports some languages by their legacy codes ("in" for Indonesian). */
private fun canonicalLanguage(language: String): String =
    when (language.lowercase(Locale.ROOT)) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> language.lowercase(Locale.ROOT)
    }

/**
 * The supported option a saved or system-reported tag means, or null for "follow the device".
 * Chinese only matches Simplified Chinese, so Traditional Chinese keeps the English fallback.
 */
internal fun appLanguageOptionFor(tag: String?): AppLanguageOption? {
    if (tag.isNullOrBlank()) return null
    val locale = Locale.forLanguageTag(tag)
    val language = canonicalLanguage(locale.language)
    if (language == "zh") {
        val traditional =
            locale.script.equals("Hant", ignoreCase = true) ||
                (locale.script.isEmpty() && locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO"))
        return if (traditional) null else APP_LANGUAGES.first { it.tag == "zh-CN" }
    }
    return APP_LANGUAGES.firstOrNull { canonicalLanguage(Locale.forLanguageTag(it.tag).language) == language }
}

/** A language's name in the interface language, falling back to the English name the catalog stores. */
internal fun languageDisplayName(
    code: String,
    interfaceLocale: Locale,
): String {
    val english = TranslationLanguages.displayName(code)
    if (canonicalLanguage(interfaceLocale.language) == "en") return english
    val language = TranslationLanguages.normalize(code)
    val localized = Locale.forLanguageTag(language).getDisplayLanguage(interfaceLocale)
    if (localized.isBlank() || localized.equals(language, ignoreCase = true)) return english
    return localized.replaceFirstChar { it.titlecase(interfaceLocale) }
}

/** The locale the interface is currently shown in, for formatting and language names. */
internal fun Context.interfaceLocale(): Locale = resources.interfaceLocale()

private fun Resources.interfaceLocale(): Locale = configuration.locales[0] ?: Locale.getDefault()

/** The interface locale inside composables, read from the resources the UI's text comes from. */
@Composable
internal fun currentInterfaceLocale(): Locale = LocalResources.current.interfaceLocale()

/**
 * Reads, saves, and applies the interface language. Android 13+ keeps the choice itself
 * (Settings > Apps > Language shows and changes the same value); older versions keep it in
 * app preferences and apply it by wrapping the activity's and view model's context.
 */
internal object AppLanguageSettings {
    @Volatile private var applicationContext: Pair<String, Context>? = null

    private fun preferences(context: Context) = context.getSharedPreferences("dual_sub_preferences", 0)

    /** The chosen language, or null when the app follows the device. */
    fun selected(context: Context): AppLanguageOption? = appLanguageOptionFor(selectedTag(context))

    private fun selectedTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context
                .getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeUnless { it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
        } else {
            preferences(context).getString(APP_LANGUAGE_PREFERENCE, null)
        }

    /** Saves [option] (null follows the device) and shows the activity in it. */
    fun select(
        activity: Activity,
        option: AppLanguageOption?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android recreates the activity in the new language itself.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                option?.let { LocaleList.forLanguageTags(it.tag) } ?: LocaleList.getEmptyLocaleList()
            return
        }
        preferences(activity)
            .edit()
            .apply {
                if (option == null) remove(APP_LANGUAGE_PREFERENCE) else putString(APP_LANGUAGE_PREFERENCE, option.tag)
            }.apply()
        activity.recreate()
    }

    /** [base] showing the chosen language; Android 13+ already applies it, so [base] is returned as is. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = selectedTag(base)?.takeIf { appLanguageOptionFor(it) != null }
        if (tag == null) {
            // Back to the device language after a choice was cleared in this process.
            Resources
                .getSystem()
                .configuration.locales[0]
                ?.let(Locale::setDefault)
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        // The view model asks for every status message; reuse the application's wrapped context.
        if (base is Application) applicationContext?.takeIf { it.first == tag }?.let { return it.second }
        // An otherwise empty override keeps orientation, size, and font scale following the device.
        val wrapped = base.createConfigurationContext(Configuration().apply { setLocales(LocaleList(locale)) })
        if (base is Application) applicationContext = tag to wrapped
        return wrapped
    }
}
