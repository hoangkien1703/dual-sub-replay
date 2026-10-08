package com.kienhoang.dualsubreplay.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AiConversationTest {
    private fun message(
        index: Int,
        role: AiRole,
        text: String = "message $index",
        context: String? = null,
    ) = AiChatMessage("m$index", role, text, index.toLong(), context)

    @Test
    fun theSystemPromptComesFirstThenTheChatInOrder() {
        val history = listOf(message(1, AiRole.USER), message(2, AiRole.ASSISTANT), message(3, AiRole.USER, context = "line: 本当に"))
        val wire = buildAiRequestMessages("guide", history)
        assertEquals(listOf(AiRole.SYSTEM, AiRole.USER, AiRole.ASSISTANT, AiRole.USER), wire.map { it.role })
        assertEquals("guide", wire.first().content)
        assertEquals("message 3\n\nline: 本当に", wire.last().content)
    }

    @Test
    fun onlyTheLatestMessagesThatFitAreSentAndTheyStartWithAQuestion() {
        val history = (1..40).map { message(it, if (it % 2 == 1) AiRole.USER else AiRole.ASSISTANT) }
        val wire = buildAiRequestMessages("guide", history, maxMessages = 5)
        // 5 newest are 36..40; 36 is an answer, so the kept part starts at 37.
        assertEquals(listOf("guide", "message 37", "message 38", "message 39", "message 40"), wire.map { it.content })
        assertEquals(AiRole.USER, wire[1].role)
    }

    @Test
    fun theNewestQuestionGoesEvenWhenItIsLong() {
        val long = "x".repeat(50)
        val wire =
            buildAiRequestMessages(
                "guide",
                listOf(message(1, AiRole.USER), message(2, AiRole.ASSISTANT), message(3, AiRole.USER, long)),
                maxChars = 40,
            )
        assertEquals(listOf("guide", long), wire.map { it.content })
    }

    @Test
    fun aChatIsNamedAfterItsFirstQuestion() {
        val chat = AiChat("c", 0, 0, listOf(message(1, AiRole.USER, "What does に mean here?\nsecond line"), message(2, AiRole.ASSISTANT)))
        assertEquals("What does に mean here?", chat.title)
        val long = AiChat("c", 0, 0, listOf(message(1, AiRole.USER, "a".repeat(80))))
        assertEquals("a".repeat(60) + "…", long.title)
    }
}
