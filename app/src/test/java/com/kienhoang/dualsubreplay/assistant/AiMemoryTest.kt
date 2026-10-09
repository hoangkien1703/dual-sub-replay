package com.kienhoang.dualsubreplay.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiMemoryTest {
    private val n4 = AiMemory("m1", "Studies for JLPT N4", 10)
    private val short = AiMemory("m2", "Likes short answers", 20)
    private val data = AiMemoryData("Explain in Vietnamese.", listOf(n4, short))
    private var ids = 0
    private val newId = { "new${ids++}" }

    private fun done(change: AiMemoryChange) = change as AiMemoryChange.Done

    @Test
    fun aMemoryIsOneShortLine() {
        assertEquals("Studies for JLPT N3", aiMemoryText("  Studies for\n JLPT   N3 "))
        assertNull(aiMemoryText("   "))
        assertNull(aiMemoryText("a".repeat(MAX_AI_MEMORY_CHARS + 1)))
        assertEquals(MAX_AI_MEMORY_CHARS, aiMemoryText("a".repeat(MAX_AI_MEMORY_CHARS))?.length)
    }

    @Test
    fun thePromptListsInstructionsThenNumberedMemories() {
        val on = aiMemoryPrompt(" Explain in Vietnamese. ", listOf(n4, short), AiMemoryUse.ON)
        assertEquals(
            "\n\nThe user's own instructions for you, from AI settings (follow them unless they break the rules above):\n" +
                "Explain in Vietnamese.\n\n" +
                "Saved memories (notes from what the user told you earlier; 2 of 30 used):\n1. Studies for JLPT N4\n2. Likes short answers",
            on,
        )
        assertEquals("\n\nSaved memories: none yet.", aiMemoryPrompt("", emptyList(), AiMemoryUse.ON))
        assertTrue("Use memory can be turned on in AI settings" in aiMemoryPrompt("", emptyList(), AiMemoryUse.OFF_IN_SETTINGS))
        val chatOff = aiMemoryPrompt("Be brief.", emptyList(), AiMemoryUse.OFF_IN_CHAT)
        assertTrue("Be brief." in chatOff)
        assertTrue(chatOff.endsWith("Memory is off for this chat: you cannot see or save memories here."))
        assertEquals(AiMemoryUse.OFF_IN_SETTINGS, aiMemoryUse(memoryEnabled = false, chatUsesMemory = true))
        assertEquals(AiMemoryUse.OFF_IN_CHAT, aiMemoryUse(memoryEnabled = true, chatUsesMemory = false))
    }

    @Test
    fun savesAreCheckedBeforeTheyRun() {
        val save = AiAction.SaveMemory("Studies for JLPT N3")
        assertNull(aiMemoryRefusal(data, save, AiMemoryUse.ON))
        assertTrue("off in AI settings" in aiMemoryRefusal(data, save, AiMemoryUse.OFF_IN_SETTINGS)!!)
        assertEquals("Memory is off for this chat.", aiMemoryRefusal(data, save, AiMemoryUse.OFF_IN_CHAT))
        val stale = AiMemory("gone", "Old", 1)
        assertEquals("That memory was already changed or forgotten.", aiMemoryRefusal(data, save.copy(replaces = stale), AiMemoryUse.ON))
        assertEquals("That memory was already forgotten.", aiMemoryRefusal(data, AiAction.ForgetMemory(stale), AiMemoryUse.ON))
        val full = AiMemoryData(memories = (1..MAX_AI_MEMORIES).map { AiMemory("f$it", "Fact $it", it.toLong()) })
        assertTrue(aiMemoryRefusal(full, save, AiMemoryUse.ON)!!.startsWith("Memory is full (30 saved)."))
        // Replacing one, or saying one again, still works when full.
        assertNull(aiMemoryRefusal(full, save.copy(replaces = full.memories[0]), AiMemoryUse.ON))
        assertNull(aiMemoryRefusal(full, AiAction.SaveMemory("fact 3"), AiMemoryUse.ON))
        // Undo is never refused, even with memory off.
        assertNull(aiMemoryRefusal(data, AiAction.RestoreMemory(n4), AiMemoryUse.OFF_IN_SETTINGS))
        assertNull(aiMemoryRefusal(data, AiAction.Playback(AiPlayback.PAUSE), AiMemoryUse.OFF_IN_SETTINGS))
    }

    @Test
    fun aSaveAddsAtTheEndAndUndoRemovesIt() {
        val change = done(changeAiMemory(data, AiAction.SaveMemory("Exam in December"), newId, 30))
        assertEquals(AiMemoryEvent.SAVED, change.event)
        assertEquals(listOf(n4, short, AiMemory("new0", "Exam in December", 30)), change.data.memories)
        assertEquals("Explain in Vietnamese.", change.data.instructions)
        assertEquals("Saved the memory \"Exam in December\"", change.note)
        assertEquals(listOf<AiAction>(AiAction.RemoveMemory("new0")), change.undo)
        val undone = done(changeAiMemory(change.data, change.undo.single(), newId, 40))
        assertEquals(data, undone.data)
    }

    @Test
    fun anUpdateTakesTheOldOnesPlaceAndUndoPutsItBack() {
        val change = done(changeAiMemory(data, AiAction.SaveMemory("Studies for JLPT N3", n4), newId, 30))
        assertEquals(AiMemoryEvent.UPDATED, change.event)
        assertEquals(listOf(AiMemory("new0", "Studies for JLPT N3", 10), short), change.data.memories)
        assertEquals("Saved the memory \"Studies for JLPT N3\" in place of \"Studies for JLPT N4\"", change.note)
        val undone = change.undo.fold(change.data) { current, action -> done(changeAiMemory(current, action, newId, 40)).data }
        assertEquals(data, undone)
    }

    @Test
    fun forgettingAndSavingTwice() {
        val forgot = done(changeAiMemory(data, AiAction.ForgetMemory(short), newId, 30))
        assertEquals(AiMemoryEvent.FORGOT, forgot.event)
        assertEquals(listOf(n4), forgot.data.memories)
        assertEquals(data, done(changeAiMemory(forgot.data, forgot.undo.single(), newId, 40)).data)
        assertTrue(changeAiMemory(forgot.data, AiAction.ForgetMemory(short), newId, 50) is AiMemoryChange.Refused)

        val again = done(changeAiMemory(data, AiAction.SaveMemory("likes  SHORT answers"), newId, 30))
        assertEquals(AiMemoryEvent.ALREADY_SAVED, again.event)
        assertEquals(data, again.data)
        assertTrue(again.undo.isEmpty())
        // Restoring twice keeps one copy.
        assertEquals(data, done(changeAiMemory(data, AiAction.RestoreMemory(n4), newId, 30)).data)
    }

    @Test
    fun memoryRoundTripsAndBadEntriesAreSkipped() {
        assertEquals(data, decodeAiMemory(encodeAiMemory(data)))
        assertEquals(AiMemoryData(), decodeAiMemory("not json"))
        val messy =
            """{"version":1,"instructions":"${"i".repeat(MAX_AI_INSTRUCTIONS_CHARS + 5)}","memories":[
                {"id":"a","text":"Fine","created":1},{"id":"","text":"No id"},{"id":"b","text":"  "},7]}"""
        val decoded = decodeAiMemory(messy)
        assertEquals(MAX_AI_INSTRUCTIONS_CHARS, decoded.instructions.length)
        assertEquals(listOf(AiMemory("a", "Fine", 1)), decoded.memories)
    }

    @Test
    fun theStoreWritesAWholeFile() {
        val root = Files.createTempDirectory("ai-memory").toFile()
        try {
            val store = AiMemoryStore(File(root, AI_MEMORY_DIRECTORY))
            assertEquals(AiMemoryData(), store.load())
            store.save(data)
            assertEquals(data, AiMemoryStore(File(root, AI_MEMORY_DIRECTORY)).load())
            store.save(AiMemoryData())
            assertEquals(AiMemoryData(), store.load())
            assertFalse(File(root, "$AI_MEMORY_DIRECTORY/memory.json.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
