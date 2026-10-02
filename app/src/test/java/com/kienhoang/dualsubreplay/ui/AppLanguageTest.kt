package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Interface languages: device fallback rules, and every offered language fully translated. */
class AppLanguageTest {
    private val resources = File("src/main/res")
    private val folders =
        mapOf(
            "vi" to "values-vi",
            "es" to "values-es",
            "pt-BR" to "values-pt-rBR",
            "ja" to "values-ja",
            "ko" to "values-ko",
            "zh-CN" to "values-zh-rCN",
            "id" to "values-in",
        )

    @Test
    fun savedAndSystemTagsMapToTheOfferedLanguages() {
        assertNull(appLanguageOptionFor(null))
        assertNull(appLanguageOptionFor(""))
        assertEquals("vi", appLanguageOptionFor("vi-VN")?.tag)
        assertEquals("id", appLanguageOptionFor("in")?.tag)
        assertEquals("id", appLanguageOptionFor("id-ID")?.tag)
        assertEquals("pt-BR", appLanguageOptionFor("pt-PT")?.tag)
        assertEquals("zh-CN", appLanguageOptionFor("zh-Hans-CN")?.tag)
        assertEquals("zh-CN", appLanguageOptionFor("zh-SG")?.tag)
        assertNull(appLanguageOptionFor("zh-TW"))
        assertNull(appLanguageOptionFor("zh-Hant"))
        assertNull(appLanguageOptionFor("fr-FR"))
        APP_LANGUAGES.forEach { assertEquals(it, appLanguageOptionFor(it.tag)) }
    }

    @Test
    fun languageNamesFollowTheInterfaceLanguage() {
        assertEquals("Japanese", languageDisplayName("ja", Locale.ENGLISH))
        assertEquals("Japanese", languageDisplayName("ja", Locale.US))
        assertEquals("日本語", languageDisplayName("ja", Locale.JAPANESE))
        assertEquals("Japonés", languageDisplayName("ja", Locale.forLanguageTag("es")))
        assertEquals("Tiếng Nhật", languageDisplayName("ja", Locale.forLanguageTag("vi")))
    }

    @Test
    fun theLinkSentenceSplitsAroundItsSlot() {
        assertEquals("Check it on " to ".", splitAroundLink("Check it on %1\$s."))
        assertEquals("" to " で確認", splitAroundLink("%1\$s で確認"))
        assertEquals("No slot " to "", splitAroundLink("No slot"))
    }

    @Test
    fun localeConfigOffersExactlyTheTranslatedLanguages() {
        val config = parse(File(resources, "xml/locales_config.xml"))
        val names = config.getElementsByTagName("locale")
        val offered = (0 until names.length).map { (names.item(it) as Element).getAttribute("android:name") }
        assertEquals(APP_LANGUAGES.map { it.tag }, offered)
        assertEquals(APP_LANGUAGES.map { it.tag }.filter { it != "en" }.toSet(), folders.keys)
        val translated =
            resources
                .listFiles()
                .orEmpty()
                .filter { dir -> dir.name.startsWith("values-") && strings(dir).isNotEmpty() }
                .map { it.name }
                .toSet()
        assertEquals(folders.values.toSet(), translated)
    }

    @Test
    fun everyLanguageTranslatesEveryStringWithTheSamePlaceholders() {
        val english = strings(File(resources, "values"))
        assertTrue(english.isNotEmpty())
        for ((tag, folder) in folders) {
            val translated = strings(File(resources, folder))
            assertEquals("$tag keys", english.keys, translated.keys)
            for ((name, placeholders) in english) {
                assertEquals("$tag $name", placeholders, translated.getValue(name))
            }
        }
    }

    /** Translatable string and plural names in a values folder, each with its format placeholders. */
    private fun strings(folder: File): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        folder
            .listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .forEach { file ->
                val root = parse(file)
                for (tag in listOf("string", "plurals")) {
                    val nodes = root.getElementsByTagName(tag)
                    for (index in 0 until nodes.length) {
                        val element = nodes.item(index) as Element
                        if (element.getAttribute("translatable") == "false") continue
                        result[element.getAttribute("name")] = placeholders(element.textContent)
                    }
                }
            }
        return result
    }

    private fun placeholders(text: String): Set<String> =
        PLACEHOLDER
            .findAll(text.replace("%%", ""))
            .map { it.value }
            .toSet()

    private fun parse(file: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private companion object {
        val PLACEHOLDER = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
    }
}
