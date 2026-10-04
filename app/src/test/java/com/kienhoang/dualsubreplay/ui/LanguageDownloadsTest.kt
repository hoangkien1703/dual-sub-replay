package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LanguageDownloadsTest {
    private val names = mapOf("en" to "English", "ja" to "Japanese", "vi" to "Vietnamese", "fr" to "French", "de" to "German")

    private class FakePacks(
        var available: List<String> = listOf("ja", "vi", "fr"),
        var downloaded: MutableSet<String> = mutableSetOf(),
    ) : LanguagePacks {
        val downloads = mutableListOf<String>()
        val removals = mutableListOf<String>()
        var failDownload = false
        var failList = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun available(): List<String> {
            if (failList) throw IOException("offline")
            return available
        }

        override suspend fun downloaded(): Set<String> = downloaded.toSet()

        override suspend fun download(code: String) {
            downloads += code
            gate?.await()
            if (failDownload) throw IOException("offline")
            downloaded += code
        }

        override suspend fun remove(code: String) {
            removals += code
            downloaded -= code
        }
    }

    private fun controller(packs: FakePacks) = LanguageDownloadsController(CoroutineScope(Dispatchers.Unconfined), packs)

    @Test
    fun groupsPutEnglishFirstThenDownloadedThenAvailableSortedByName() {
        val groups =
            languageDownloadGroups(
                LanguageDownloadsState(available = listOf("vi", "ja", "fr", "de"), downloaded = setOf("vi", "fr")),
                nameOf = names::getValue,
            )

        assertEquals(listOf("en", "fr", "vi"), groups.onDevice.map { it.code })
        assertEquals(LanguageDownloadStatus.BUILT_IN, groups.onDevice.first().status)
        assertEquals(LanguageDownloadStatus.DOWNLOADED, groups.onDevice[1].status)
        assertEquals(listOf("de", "ja"), groups.available.map { it.code })
        assertTrue(groups.available.all { it.status == LanguageDownloadStatus.NOT_DOWNLOADED })
    }

    @Test
    fun busyAndFailedLanguagesStayInTheirGroupWithTheirStatus() {
        val groups =
            languageDownloadGroups(
                LanguageDownloadsState(
                    available = listOf("ja", "vi", "fr"),
                    downloaded = setOf("vi"),
                    downloading = setOf("ja"),
                    removing = setOf("vi"),
                    failed = setOf("fr"),
                ),
                nameOf = names::getValue,
            )

        assertEquals(LanguageDownloadStatus.REMOVING, groups.onDevice.single { it.code == "vi" }.status)
        assertEquals(LanguageDownloadStatus.DOWNLOADING, groups.available.single { it.code == "ja" }.status)
        assertEquals(LanguageDownloadStatus.FAILED, groups.available.single { it.code == "fr" }.status)
    }

    @Test
    fun englishIsNotListedTwiceWhenTheEngineReportsIt() {
        val groups = languageDownloadGroups(LanguageDownloadsState(available = listOf("en", "ja")), nameOf = names::getValue)

        assertEquals(listOf("en"), groups.onDevice.map { it.code })
        assertEquals(listOf("ja"), groups.available.map { it.code })
    }

    @Test
    fun refreshLoadsAvailableAndDownloadedLanguages() {
        val packs = FakePacks(downloaded = mutableSetOf("vi"))
        val controller = controller(packs)

        controller.refresh()

        val state = controller.state.value
        assertFalse(state.loading)
        assertFalse(state.loadFailed)
        assertEquals(listOf("ja", "vi", "fr"), state.available)
        assertEquals(setOf("vi"), state.downloaded)
    }

    @Test
    fun aFailedListLoadIsReportedAndRetryClearsIt() {
        val packs = FakePacks().apply { failList = true }
        val controller = controller(packs)

        controller.refresh()
        assertTrue(controller.state.value.loadFailed)

        packs.failList = false
        controller.refresh()
        assertFalse(controller.state.value.loadFailed)
        assertEquals(listOf("ja", "vi", "fr"), controller.state.value.available)
    }

    @Test
    fun downloadMarksTheLanguageDownloadedAndIgnoresASecondTapWhileBusy() {
        val packs = FakePacks().apply { gate = CompletableDeferred() }
        val controller = controller(packs)
        controller.refresh()

        controller.download("ja")
        assertEquals(setOf("ja"), controller.state.value.downloading)
        controller.download("ja")
        controller.remove("ja")
        assertEquals(listOf("ja"), packs.downloads)
        assertTrue(packs.removals.isEmpty())

        packs.gate!!.complete(Unit)
        assertEquals(emptySet<String>(), controller.state.value.downloading)
        assertEquals(setOf("ja"), controller.state.value.downloaded)
    }

    @Test
    fun aFailedDownloadIsMarkedAndATryAgainClearsIt() {
        val packs = FakePacks().apply { failDownload = true }
        val controller = controller(packs)

        controller.download("vi")
        assertEquals(setOf("vi"), controller.state.value.failed)
        assertEquals(emptySet<String>(), controller.state.value.downloaded)

        packs.failDownload = false
        controller.download("vi")
        assertEquals(emptySet<String>(), controller.state.value.failed)
        assertEquals(setOf("vi"), controller.state.value.downloaded)
    }

    @Test
    fun removeDropsTheLanguageFromDownloaded() {
        val packs = FakePacks(downloaded = mutableSetOf("fr"))
        val controller = controller(packs)
        controller.refresh()

        controller.remove("fr")

        assertEquals(listOf("fr"), packs.removals)
        assertEquals(emptySet<String>(), controller.state.value.downloaded)
        assertEquals(emptySet<String>(), controller.state.value.removing)
    }

    @Test
    fun japaneseNeedsItsDictionaryToCountAsDownloaded() =
        runBlocking {
            var dictionary = false
            var installs = 0
            var removals = 0
            val packs =
                LanguagePacksWithJapaneseDictionary(
                    models = FakePacks(downloaded = mutableSetOf("ja", "vi")),
                    dictionaryInstalled = { dictionary },
                    installDictionary = {
                        installs++
                        dictionary = true
                        true
                    },
                    removeDictionary = {
                        removals++
                        dictionary = false
                    },
                )

            assertEquals(setOf("vi"), packs.downloaded())
            packs.download("vi")
            assertEquals(0, installs)
            packs.download("ja")
            assertEquals(1, installs)
            assertEquals(setOf("ja", "vi"), packs.downloaded())
            packs.remove("ja")
            assertEquals(1, removals)
            assertEquals(setOf("vi"), packs.downloaded())
        }

    @Test(expected = IOException::class)
    fun aJapaneseDownloadFailsWhenTheDictionaryCannotBeDownloaded() {
        runBlocking {
            LanguagePacksWithJapaneseDictionary(
                models = FakePacks(),
                dictionaryInstalled = { false },
                installDictionary = { false },
                removeDictionary = {},
            ).download("ja")
        }
    }
}
