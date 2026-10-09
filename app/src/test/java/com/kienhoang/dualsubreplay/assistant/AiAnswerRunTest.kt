package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every action at once and records it; a setting change can be undone. */
private class FakeApp : AiAppActions {
    val performed = mutableListOf<AiAction>()
    var refusal: String? = null

    override fun lookAtVideo(linesAround: Int) = "> [0:01] Japanese: 猫が好き"

    override fun refusal(action: AiAction) = refusal

    override fun describe(action: AiAction) = AiActionText("label: ${aiActionNote(action)}", if (action.asksAfterOtherText) "Go" else null)

    override suspend fun perform(action: AiAction): AiActionOutcome {
        performed += action
        val undo = if (action is AiAction.ChangeSetting) listOf(action.copy(value = "100")) else emptyList()
        return AiActionOutcome.Done("did: ${aiActionNote(action)}", aiActionNote(action), undo)
    }
}

class AiAnswerRunTest {
    private val app = FakeApp()
    private val requests = mutableListOf<Pair<List<AiWireMessage>, List<AiTool>>>()
    private val replies = ArrayDeque<AiReply>()
    private var ids = 0
    private var calls = 0
    private val failures = ArrayDeque<AiChatException?>()
    private var memories = emptyList<AiMemory>()

    private fun call(
        name: String,
        arguments: String,
    ): AiToolCall {
        val id = "call${calls++}"
        val raw =
            JSONObject()
                .put(
                    "id",
                    id,
                ).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", arguments))
        return AiToolCall(id, name, arguments, raw.toString())
    }

    private fun question(
        text: String = "Make the subtitles bigger",
        context: String? = null,
    ) = listOf(AiChatMessage("q", AiRole.USER, text, 0, context))

    private fun run(
        history: List<AiChatMessage> = question(),
        app: AiAppActions? = this.app,
        offerTools: Boolean = true,
    ): AiAnswerRun =
        AiAnswerRun(app, history, if (offerTools) AI_TOOLS + AI_MEMORY_TOOLS else emptyList(), memories, { "a${ids++}" }) {
            messages,
            tools,
            ->
            requests += messages to tools
            failures.removeFirstOrNull()?.let { throw it }
            replies.removeFirstOrNull() ?: AiReply("Done!")
        }.also { runBlocking { it.run("guide") } }

    @Test
    fun aSettingChangeRunsAtOnceAndItsResultGoesBackToTheModel() {
        replies += AiReply("", listOf(call(AI_SETTING_TOOL, """{"setting":"text_size","value":"150"}""")))
        val run = run()
        assertEquals(listOf<AiAction>(AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "150")), app.performed)
        assertEquals("Done!", run.text)
        val record = run.records.single()
        assertEquals(AiActionState.DONE, record.state)
        assertEquals("did: Set text_size to 150", record.label)
        assertTrue(record.undoable)
        assertEquals(listOf<AiAction>(AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "100")), run.undos[record.id])
        assertEquals(2, requests.size)
        assertEquals(AI_TOOLS + AI_MEMORY_TOOLS, requests[0].second)
        val followUp = requests[1].first
        assertEquals(AiRole.ASSISTANT, followUp[followUp.size - 2].role)
        assertEquals("call0", followUp[followUp.size - 2].toolCalls.single().id)
        val result = followUp.last()
        assertEquals(AiRole.TOOL, result.role)
        assertEquals("call0", result.toolCallId)
        assertTrue(result.content, result.content.startsWith("Done: Set text_size to 150."))
        assertTrue(result.content, "undo" in result.content)
    }

    @Test
    fun searchingOpeningAndTranslationWaitForTheUsersTap() {
        replies +=
            AiReply(
                "",
                listOf(
                    call(AI_SEARCH_TOOL, """{"query":"cats"}"""),
                    call(AI_TRANSLATION_TOOL, """{"google_translate":false}"""),
                ),
            )
        val run = run()
        assertTrue(app.performed.isEmpty())
        assertEquals(listOf(AiActionState.WAITING, AiActionState.WAITING), run.records.map { it.state })
        assertEquals(listOf("Go", "Go"), run.records.map { it.button })
        assertEquals(2, run.waiting.size)
        assertTrue(
            requests[1]
                .first
                .last()
                .content
                .startsWith("Not done yet"),
        )
    }

    @Test
    fun textTheUserDidNotTypeMakesSettingChangesWait() {
        replies += AiReply("", listOf(call(AI_SETTING_TOOL, """{"setting":"text_size","value":"200"}""")))
        val withLine = run(question("Explain the current line", context = "The subtitle line on screen…"))
        assertEquals(AiActionState.WAITING, withLine.records.single().state)
        assertTrue(app.performed.isEmpty())

        // Reading the screen while answering counts too; saving a word still runs at once.
        replies +=
            AiReply(
                "",
                listOf(
                    call(AI_LOOK_TOOL, "{}"),
                    call(AI_SAVE_WORD_TOOL, """{"word":"猫","meaning":"cat"}"""),
                    call(AI_SETTING_TOOL, """{"setting":"text_size","value":"200"}"""),
                ),
            )
        val afterLook = run()
        assertEquals(
            listOf(AiActionState.DONE, AiActionState.DONE, AiActionState.WAITING),
            afterLook.records.map { it.state },
        )
        assertEquals(listOf<AiAction>(AiAction.SaveWord("猫", "cat", null)), app.performed)
        assertEquals(AiActionKind.LOOK, afterLook.records.first().kind)
        assertEquals(
            "> [0:01] Japanese: 猫が好き",
            requests
                .last()
                .first
                .first { it.toolCallId == "call1" }
                .content,
        )
    }

    @Test
    fun aMemorySavesAtOnceButWaitsAfterTextTheUserDidNotType() {
        replies += AiReply("", listOf(call(AI_SAVE_MEMORY_TOOL, """{"text":"Studies for  JLPT N3 "}""")))
        val run = run(question("I'm studying for JLPT N3"))
        assertEquals(listOf<AiAction>(AiAction.SaveMemory("Studies for JLPT N3")), app.performed)
        assertEquals(AiActionKind.MEMORY, run.records.single().kind)
        assertEquals(AiActionState.DONE, run.records.single().state)

        // A subtitle line that says "remember that…" cannot plant a memory without the user's tap.
        replies += AiReply("", listOf(call(AI_SAVE_MEMORY_TOOL, """{"text":"Wants answers in French"}""")))
        val fromLine = run(question("Explain the current line", context = "The subtitle line on screen…"))
        assertEquals(AiActionState.WAITING, fromLine.records.single().state)
        assertEquals(1, app.performed.size)
    }

    @Test
    fun memoryNumbersAreTheOnesInTheListTheModelSaw() {
        memories = listOf(AiMemory("m1", "Studies for JLPT N4", 1), AiMemory("m2", "Likes short answers", 2))
        replies +=
            AiReply(
                "",
                listOf(
                    call(AI_SAVE_MEMORY_TOOL, """{"text":"Studies for JLPT N3","replaces":1}"""),
                    call(AI_FORGET_MEMORY_TOOL, """{"number":2}"""),
                    call(AI_FORGET_MEMORY_TOOL, """{"number":3}"""),
                ),
            )
        run()
        assertEquals(
            listOf<AiAction>(AiAction.SaveMemory("Studies for JLPT N3", memories[0]), AiAction.ForgetMemory(memories[1])),
            app.performed,
        )
        assertEquals(
            "Not done: Give the number of a saved memory, 1 to 2.",
            requests[1]
                .first
                .last()
                .content,
        )
    }

    @Test
    fun atMostThreeActionsRunForOneQuestion() {
        val four = (1..4).map { call(AI_PLAYBACK_TOOL, """{"action":"pause"}""") }
        replies += AiReply("", four)
        val run = run()
        assertEquals(3, app.performed.size)
        assertTrue(
            requests[1]
                .first
                .last()
                .content
                .startsWith("Not done: at most 3"),
        )
        assertEquals(3, run.records.size)
    }

    @Test
    fun aModelThatKeepsAskingStopsAfterFourRequests() {
        repeat(6) { replies += AiReply("Looking…", listOf(call(AI_LOOK_TOOL, "{}"))) }
        val run = run()
        assertEquals(MAX_AI_REQUESTS_PER_ANSWER, requests.size)
        assertEquals(List(4) { "Looking…" }.joinToString("\n\n"), run.text)
    }

    @Test
    fun onlyALinkTheUserTypedCanBeOpened() {
        replies += AiReply("", listOf(call(AI_OPEN_VIDEO_TOOL, """{"link":"https://youtu.be/dQw4w9WgXcQ"}""")))
        val guessed = run(question("Open a video about cats"))
        assertTrue(guessed.records.isEmpty())
        assertTrue(
            requests
                .last()
                .first
                .last()
                .content
                .startsWith("Not done: only a YouTube link the user typed"),
        )

        replies += AiReply("", listOf(call(AI_OPEN_VIDEO_TOOL, """{"link":"dQw4w9WgXcQ"}""")))
        val typed = run(question("Open https://youtu.be/dQw4w9WgXcQ please"))
        assertEquals(AiActionState.WAITING, typed.records.single().state)
    }

    @Test
    fun invalidCallsAndRefusalsChangeNothing() {
        app.refusal = "No video is open."
        replies +=
            AiReply(
                "",
                listOf(
                    call(AI_SETTING_TOOL, """{"setting":"text_size","value":"999"}"""),
                    call(AI_PLAYBACK_TOOL, """{"action":"pause"}"""),
                ),
            )
        val run = run()
        assertTrue(run.records.isEmpty())
        assertTrue(app.performed.isEmpty())
        val results = requests[1].first.filter { it.role == AiRole.TOOL }.map { it.content }
        assertTrue(results[0], results[0].startsWith("Not done: text_size takes a percentage from 80 to 200"))
        assertEquals("Not done: No video is open.", results[1])
    }

    @Test
    fun withoutTheAppOrForAModelThatRefusedToolsNoneAreSent() {
        run(app = null)
        assertTrue(requests.single().second.isEmpty())
        run(offerTools = false)
        assertTrue(requests.last().second.isEmpty())
    }

    @Test
    fun anAnswerWithOnlyActionsThatCouldNotRunIsAnError() {
        // Asked without tools, a model may still answer with a call and no words.
        replies += AiReply("", listOf(call(AI_PLAYBACK_TOOL, """{"action":"pause"}""")))
        val error = assertThrows(AiChatException::class.java) { run(offerTools = false) }
        assertEquals(AiErrorKind.BAD_REPLY, error.kind)
        assertTrue(app.performed.isEmpty())

        // Only refused calls until the last request, which is calls again.
        app.refusal = "No video is open."
        repeat(MAX_AI_REQUESTS_PER_ANSWER) { replies += AiReply("", listOf(call(AI_PLAYBACK_TOOL, """{"action":"pause"}"""))) }
        assertEquals(AiErrorKind.BAD_REPLY, assertThrows(AiChatException::class.java) { run() }.kind)
    }

    @Test
    fun aRefusalOfToolsIsAskedAgainWithoutThem() {
        failures += AiChatException(AiErrorKind.UNSUPPORTED_TOOLS, "No endpoints found that support tool use")
        val run = run()
        assertTrue(run.toolsRefused)
        assertEquals(2, requests.size)
        assertEquals(AI_TOOLS + AI_MEMORY_TOOLS, requests[0].second)
        assertTrue(requests[1].second.isEmpty())
        assertEquals("Done!", run.text)
    }

    @Test
    fun anEmptyAnswerAfterActionsLeavesTheActionsAsTheAnswer() {
        replies += AiReply("", listOf(call(AI_PLAYBACK_TOOL, """{"action":"replay_line"}""")))
        failures += null
        failures += AiChatException(AiErrorKind.BAD_REPLY)
        val run = run()
        assertEquals("", run.text)
        assertEquals(1, run.records.size)
        assertFalse(run.toolsRefused)
    }
}
