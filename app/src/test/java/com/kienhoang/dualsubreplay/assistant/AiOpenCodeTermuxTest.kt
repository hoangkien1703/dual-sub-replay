package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Records each command and answers with [result]. */
private class FakeTermux(
    var result: TermuxResult = TermuxResult("", "", 0),
) : TermuxRunner {
    val runs = mutableListOf<Triple<String, List<String>, String?>>()

    override suspend fun run(
        script: String,
        args: List<String>,
        stdin: String?,
        timeoutMs: Long,
    ): TermuxResult {
        runs += Triple(script, args, stdin)
        return result
    }
}

class AiOpenCodeTermuxTest {
    // Lines as `opencode run --format json` (2.0.29) printed them.
    private val stepStart = """{"type":"step_start","timestamp":1,"sessionID":"ses_1","part":{"type":"step-start"}}"""

    private fun textEvent(text: String) =
        """{"type":"text","timestamp":2,"sessionID":"ses_1","part":{"type":"text","text":${org.json.JSONObject.quote(text)}}}"""

    private val freeTierRefusal =
        """{"type":"error","timestamp":3,"sessionID":"ses_1","error":{"type":"provider.auth","message":""" +
            """"OpenCode's free tier can only be used from within OpenCode. Download it at https://opencode.ai and keep building.","status":403}}"""
    private val rateLimit =
        """{"type":"error","timestamp":3,"sessionID":"ses_1","error":{"type":"provider.quota",""" +
            """"message":"Rate limit exceeded. Please try again later.","status":429}}"""
    private val noRoute =
        """{"type":"error","timestamp":3,"sessionID":"ses_1","error":{"type":"provider.no-route","message":"Model unavailable: opencode/x"}}"""

    private fun failureOf(block: () -> Unit): AiChatException {
        try {
            block()
        } catch (error: AiChatException) {
            return error
        }
        fail("Expected a failure")
        throw AssertionError()
    }

    @Test
    fun thePromptCarriesTheInstructionsActionsAndWholeChat() {
        val call = AiToolCall("call_0", "playback", """{"action":"pause"}""", "{}")
        val messages =
            listOf(
                AiWireMessage(AiRole.SYSTEM, "Answer in Vietnamese."),
                AiWireMessage(AiRole.USER, "Pause, please."),
                AiWireMessage(AiRole.ASSISTANT, "Pausing.", toolCalls = listOf(call)),
                AiWireMessage(AiRole.TOOL, "Done: paused.", toolCallId = "call_0"),
            )
        val tools = listOf(AiTool("playback", "Replay or pause.", """{"type":"object"}"""))
        val prompt = openCodePrompt(messages, tools)
        assertTrue(prompt.contains("Do not use any tools"))
        assertTrue(prompt.contains("<app_instructions>\nAnswer in Vietnamese.\n</app_instructions>"))
        assertTrue(prompt.contains("- playback: Replay or pause.\n  arguments: {\"type\":\"object\"}"))
        assertTrue(prompt.contains("[User]\nPause, please."))
        assertTrue(prompt.contains("[Assistant]\nPausing.\n<action>{\"name\":\"playback\",\"arguments\":{\"action\":\"pause\"}}</action>"))
        assertTrue(prompt.contains("[Action result]\nDone: paused."))
        assertTrue(prompt.indexOf("<app_instructions>") < prompt.indexOf("<chat>"))
        // Without actions on offer, the model is not told about them.
        assertFalse(openCodePrompt(messages, emptyList()).contains("<app_actions>"))
    }

    @Test
    fun theAnswerAndItsActionsComeFromTheJsonEvents() {
        val stdout =
            listOf(
                stepStart,
                "Welcome to Termux!",
                textEvent("Pausing the video now.\n<action>{\"name\": \"playback\", \"arguments\": {\"action\": \"pause\"}}</action>"),
                textEvent("Anything else?"),
            ).joinToString("\n")
        val reply = openCodeReply(TermuxResult(stdout, "", 0))
        assertEquals("Pausing the video now.\n\nAnything else?", reply.text)
        assertEquals(1, reply.toolCalls.size)
        assertEquals("playback", reply.toolCalls[0].name)
        assertEquals("pause", aiToolArguments(reply.toolCalls[0])!!.getString("action"))
    }

    @Test
    fun brokenActionLinesAreDroppedAndAnEmptyAnswerFails() {
        val reply = openCodeAnswer("Sure.\n<action>{not json}</action>\n<action>{\"arguments\":{}}</action>")
        assertEquals("Sure.", reply.text)
        assertTrue(reply.toolCalls.isEmpty())
        assertEquals(AiErrorKind.BAD_REPLY, failureOf { openCodeReply(TermuxResult(stepStart, "", 0)) }.kind)
    }

    @Test
    fun openCodeErrorsKeepTheirOwnMessage() {
        val refused = failureOf { openCodeReply(TermuxResult(freeTierRefusal, "", 1)) }
        assertEquals(AiErrorKind.BAD_REQUEST, refused.kind)
        assertTrue(refused.detail!!.startsWith("OpenCode's free tier"))
        assertEquals(AiErrorKind.RATE_LIMITED, failureOf { openCodeReply(TermuxResult(rateLimit, "", 1)) }.kind)
        assertEquals(AiErrorKind.UNKNOWN_MODEL, failureOf { openCodeReply(TermuxResult(noRoute, "", 1)) }.kind)
        // A step that fails after the model already answered still shows the answer.
        assertEquals("Hello", openCodeReply(TermuxResult(listOf(textEvent("Hello"), rateLimit).joinToString("\n"), "", 1)).text)
    }

    @Test
    fun termuxAndScriptProblemsSayWhatToFix() {
        assertEquals(AiErrorKind.OPENCODE_MISSING, failureOf { openCodeReply(TermuxResult("", "", OPENCODE_EXIT_NOT_FOUND)) }.kind)
        assertEquals(AiErrorKind.TERMUX_FAILED, failureOf { openCodeReply(TermuxResult("", "", OPENCODE_EXIT_NO_FOLDER)) }.kind)
        val notAllowed = TermuxResult("", "", null, "RUN_COMMAND requires allow-external-apps to be set to true in termux.properties")
        assertEquals(AiErrorKind.TERMUX_APPS_NOT_ALLOWED, failureOf { openCodeReply(notAllowed) }.kind)
        val crashed = failureOf { openCodeReply(TermuxResult("", "\u001B[91mSomething broke\u001B[0m", 2)) }
        assertEquals(AiErrorKind.TERMUX_FAILED, crashed.kind)
        assertEquals("Something broke", crashed.detail)
    }

    @Test
    fun aQuestionGoesOnStdinWithTheModelAsTheOnlyArgument() =
        runBlocking {
            val termux = FakeTermux(TermuxResult(textEvent("OK"), "", 0))
            val request =
                AiChatRequest(
                    baseUrl = AiProvider.OPENCODE_PHONE.baseUrl,
                    model = "opencode/big-pickle",
                    apiKey = "",
                    messages = listOf(AiWireMessage(AiRole.SYSTEM, "Be brief."), AiWireMessage(AiRole.USER, "\$(rm -rf ~); `id`")),
                    format = AiWireFormat.OPENCODE_TERMUX,
                )
            assertEquals("OK", AiOpenCodeTransport(termux).complete(request).text)
            val (script, args, stdin) = termux.runs.single()
            assertEquals(OPENCODE_RUN_SCRIPT, script)
            assertEquals(listOf("opencode/big-pickle"), args)
            assertTrue(stdin!!.contains("\$(rm -rf ~); `id`"))
            assertFalse(script.contains("rm -rf"))
        }

    @Test
    fun theScriptsKeepOpenCodesToolsAsking() {
        listOf(OPENCODE_RUN_SCRIPT, OPENCODE_MODELS_SCRIPT).forEach { script ->
            assertTrue(script.contains("printf '%s\\n' '$OPENCODE_CONFIG' > opencode.json"))
            assertTrue(script.contains("exit $OPENCODE_EXIT_NOT_FOUND"))
            // OpenCode's install script adds ~/.opencode/bin to PATH only in ~/.bashrc.
            assertTrue(script.startsWith("PATH=\"\$HOME/.opencode/bin:"))
            assertTrue(script.indexOf("PATH=") < script.indexOf("command -v opencode"))
        }
        assertEquals("""{"agent":{"plan":{"permission":"ask"}}}""", OPENCODE_CONFIG)
        assertTrue(OPENCODE_RUN_SCRIPT.endsWith("run_opencode run --agent plan --format json -m \"\$1\""))
        // In a proot-distro Linux, OpenCode still runs in the work folder that holds the config.
        assertTrue(OPENCODE_RUN_SCRIPT.contains("exec proot-distro login \"\$distro\" --work-dir \"\$dir\" -- opencode \"\$@\""))
    }

    @Test
    fun aModelNameThatIsNotProviderSlashModelNeverReachesTermux() =
        runBlocking {
            val termux = FakeTermux(TermuxResult(textEvent("OK"), "", 0))
            val request =
                AiChatRequest(
                    baseUrl = AiProvider.OPENCODE_PHONE.baseUrl,
                    model = "opencode/x; rm -rf ~",
                    apiKey = "",
                    messages = listOf(AiWireMessage(AiRole.USER, "Hi")),
                    format = AiWireFormat.OPENCODE_TERMUX,
                )
            val error =
                try {
                    AiOpenCodeTransport(termux).complete(request)
                    null
                } catch (failure: AiChatException) {
                    failure
                }
            assertEquals(AiErrorKind.UNKNOWN_MODEL, error?.kind)
            assertTrue(termux.runs.isEmpty())
            assertEquals("OK", AiOpenCodeTransport(termux).complete(request.copy(model = "openrouter/qwen/qwen3:free")).text)
        }

    @Test
    fun picturesAndPdfsAreRefusedBeforeTermuxIsAsked() =
        runBlocking {
            val termux = FakeTermux()
            val picture = AiAttachment("cat.jpg", AiAttachmentKind.PICTURE, "data:image/jpeg;base64,AAAA")
            val request =
                AiChatRequest(
                    baseUrl = AiProvider.OPENCODE_PHONE.baseUrl,
                    model = "opencode/big-pickle",
                    apiKey = "",
                    messages = listOf(AiWireMessage(AiRole.USER, "What is this?", attachments = listOf(picture))),
                    format = AiWireFormat.OPENCODE_TERMUX,
                )
            val error =
                try {
                    AiOpenCodeTransport(termux).complete(request)
                    null
                } catch (failure: AiChatException) {
                    failure
                }
            assertEquals(AiErrorKind.UNSUPPORTED_ATTACHMENT, error?.kind)
            assertTrue(termux.runs.isEmpty())
            // A text file is part of the message text and goes through.
            val text = AiAttachment("notes.txt", AiAttachmentKind.TEXT, "notes")
            assertFalse(openCodeRefusesAttachments(listOf(AiWireMessage(AiRole.USER, "Read", attachments = listOf(text)))))
        }

    @Test
    fun theModelListMarksOpenCodesFreeModels() =
        runBlocking {
            val stdout =
                listOf(
                    "opencode/big-pickle",
                    "opencode/deepseek-v4.1-flash-free",
                    "opencode/kimi-k2.6",
                    "anthropic/claude-haiku-5-5",
                    "not a model",
                ).joinToString("\n")
            val models = AiOpenCodeTransport(FakeTermux(TermuxResult(stdout, "", 0))).listModels()
            assertEquals(
                listOf("anthropic/claude-haiku-5-5", "opencode/big-pickle", "opencode/deepseek-v4.1-flash-free", "opencode/kimi-k2.6"),
                models.map { it.id },
            )
            assertEquals(listOf(false, true, true, false), models.map { it.free })
        }

    @Test
    fun onlyOpenCodeOnThisPhoneGoesToTermux() =
        runBlocking {
            val termux = FakeTermux(TermuxResult(textEvent("from termux"), "", 0))
            val routing = AiRoutingTransport(AiHttpTransport(), AiOpenCodeTransport(termux))
            val request =
                AiChatRequest(
                    AiProvider.OPENCODE_PHONE.baseUrl,
                    AiProvider.OPENCODE_PHONE.defaultModel,
                    "",
                    listOf(AiWireMessage(AiRole.USER, "Hi")),
                    format = AiProvider.OPENCODE_PHONE.format,
                )
            assertEquals("from termux", routing.complete(request).text)
            // An HTTPS service never reaches Termux; this one fails on its address before any network.
            val other = request.copy(baseUrl = "http://insecure.example/v1", format = AiWireFormat.OPENAI_CHAT)
            val error =
                try {
                    routing.complete(other)
                    null
                } catch (failure: AiChatException) {
                    failure
                }
            assertEquals(AiErrorKind.BAD_ADDRESS, error?.kind)
            assertEquals(1, termux.runs.size)
        }
}
