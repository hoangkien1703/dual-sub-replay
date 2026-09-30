package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Backups leave out the large downloads the app can fetch again, on every Android version. */
class BackupRulesTest {
    private val regenerable = setOf("japanese-dictionary/", "translation-models/")

    @Test
    fun everyBackupPathLeavesOutTheDownloadedDictionaryAndModels() {
        assertEquals(regenerable, excludedPaths(rules("backup_rules.xml")))
        val extraction = rules("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = extraction.getElementsByTagName(section).item(0) as Element
            assertEquals(section, regenerable, excludedPaths(element))
        }
    }

    @Test
    fun excludedFoldersAreTheOnesTheAppDownloadsInto() {
        val viewModel = File("src/main/java/com/kienhoang/dualsubreplay/ui/AppViewModel.kt").readText()
        for (path in regenerable) {
            assertTrue(path, "File(application.filesDir, \"${path.removeSuffix("/")}\")" in viewModel)
        }
    }

    @Test
    fun theManifestUsesBothRuleFiles() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:fullBackupContent=\"@xml/backup_rules\"" in manifest)
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
    }

    private fun rules(name: String): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/xml/$name"))
            .documentElement

    private fun excludedPaths(parent: Element): Set<String> {
        val excludes = parent.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .onEach { assertEquals("file", it.getAttribute("domain")) }
            .map { it.getAttribute("path") }
            .toSet()
    }
}
