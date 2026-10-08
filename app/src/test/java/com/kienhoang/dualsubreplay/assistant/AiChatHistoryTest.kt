package com.kienhoang.dualsubreplay.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiChatHistoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun chat(
        id: String,
        updatedDaysAgo: Int,
    ) = AiChat(
        id = id,
        createdMs = now - updatedDaysAgo * day,
        updatedMs = now - updatedDaysAgo * day,
        messages = listOf(AiChatMessage("$id-1", AiRole.USER, "question $id", now - updatedDaysAgo * day)),
    )

    @Test
    fun retentionKeepsRecentChatsNewestFirst() {
        val chats = listOf(chat("old", 40), chat("recent", 1), chat("week", 6), chat("month", 20))
        assertEquals(listOf("recent", "week"), keptAiChats(chats, ChatHistoryRetention.WEEK, now).map { it.id })
        assertEquals(listOf("recent", "week", "month"), keptAiChats(chats, ChatHistoryRetention.MONTH, now).map { it.id })
        assertEquals(listOf("recent", "week", "month", "old"), keptAiChats(chats, ChatHistoryRetention.FOREVER, now).map { it.id })
        assertTrue(keptAiChats(chats, ChatHistoryRetention.OFF, now).isEmpty())
    }

    @Test
    fun emptyChatsAreNeverKeptAndTheListIsCapped() {
        val empty = AiChat("empty", now, now, emptyList())
        assertTrue(keptAiChats(listOf(empty), ChatHistoryRetention.FOREVER, now).isEmpty())
        val many = (0 until MAX_SAVED_AI_CHATS + 20).map { chat("c$it", 0) }
        assertEquals(MAX_SAVED_AI_CHATS, keptAiChats(many, ChatHistoryRetention.FOREVER, now).size)
    }

    @Test
    fun chatsSurviveEncodingWithTheirContext() {
        val chat =
            AiChat(
                "c1",
                10,
                20,
                listOf(
                    AiChatMessage("u", AiRole.USER, "Why?", 11, context = "Title: Subtitles couldn't load", contextLabel = "Subtitles"),
                    AiChatMessage("a", AiRole.ASSISTANT, "**Because** the video has no captions.", 12),
                ),
            )
        assertEquals(listOf(chat), decodeAiChats(encodeAiChats(listOf(chat))))
    }

    @Test
    fun savedChatsKeepFileNamesButNeverTheFiles() {
        val files =
            listOf(
                AiAttachment("page.jpg", AiAttachmentKind.PICTURE, "data:image/jpeg;base64,AAAA"),
                AiAttachment("ep1.srt", AiAttachmentKind.TEXT, "こんにちは"),
            )
        val chat = AiChat("c1", 10, 20, listOf(AiChatMessage("u", AiRole.USER, "What is this?", 11, attachments = files)))
        val encoded = encodeAiChats(listOf(chat))
        assertFalse("AAAA" in encoded)
        assertFalse("こんにちは" in encoded)
        val saved =
            decodeAiChats(encoded)
                .single()
                .messages
                .single()
                .attachments
        assertEquals(listOf("page.jpg" to AiAttachmentKind.PICTURE, "ep1.srt" to AiAttachmentKind.TEXT), saved.map { it.name to it.kind })
        assertTrue(saved.none { it.available })
    }

    @Test
    fun unreadableHistoryIsSkippedNotFatal() {
        assertTrue(decodeAiChats("not json").isEmpty())
        assertTrue(decodeAiChats("""{"chats":[{"messages":[]}]}""").isEmpty())
        val partial =
            decodeAiChats(
                """{"chats":[{"id":"c","messages":[{"id":"m","role":"system","text":"x"},{"id":"n","role":"user","text":"hi"}]}]}""",
            )
        assertEquals(listOf("hi"), partial.single().messages.map { it.text })
    }

    @Test
    fun theStoreWritesReadsAndClears() {
        val root = Files.createTempDirectory("ai-history").toFile()
        try {
            val directory = File(root, AI_CHATS_DIRECTORY)
            val store = AiChatHistoryStore(directory)
            assertTrue(store.load().isEmpty())
            store.save(listOf(chat("a", 0)))
            assertEquals(listOf("a"), store.load().map { it.id })
            assertFalse(File(directory, "chats.json.tmp").exists())
            store.save(emptyList())
            assertFalse(directory.exists())
            store.save(listOf(chat("b", 0)))
            store.clear()
            assertTrue(store.load().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
