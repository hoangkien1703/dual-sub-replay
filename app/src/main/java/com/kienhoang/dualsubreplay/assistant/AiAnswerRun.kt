package com.kienhoang.dualsubreplay.assistant

/**
 * One answer: asks the service, runs the actions it asks for, and asks again with their results,
 * at most [MAX_AI_REQUESTS_PER_ANSWER] times. What it has done so far stays readable after a
 * failure, so an action that already ran still shows with its Undo. [tools] are offered only with
 * [app]; [memories] is the numbered list of saved memories the model sees with this answer.
 */
internal class AiAnswerRun(
    private val app: AiAppActions?,
    private val history: List<AiChatMessage>,
    tools: List<AiTool>,
    private val memories: List<AiMemory>,
    private val newId: () -> String,
    private val ask: suspend (messages: List<AiWireMessage>, tools: List<AiTool>) -> AiReply,
) {
    private val texts = mutableListOf<String>()
    val records = mutableListOf<AiActionRecord>()

    /** The actions that undo each done action that can be undone, by record id. */
    val undos = mutableMapOf<String, List<AiAction>>()

    /** Actions waiting for the user's tap, by record id. */
    val waiting = mutableMapOf<String, AiAction>()

    /** The model refused the actions; it was asked again without them. */
    var toolsRefused = false
        private set

    private var tools = if (app != null) tools else emptyList()

    /** Subtitle lines, problem details, files or pictures came with the question or were read while answering. */
    private var otherText = history.lastOrNull()?.let { it.context != null || it.attachments.isNotEmpty() } == true
    private var changes = 0

    val text: String get() = texts.joinToString("\n\n")

    suspend fun run(systemPrompt: String) {
        val messages = buildAiRequestMessages(systemPrompt, history).toMutableList()
        for (round in 1..MAX_AI_REQUESTS_PER_ANSWER) {
            val reply = request(messages, firstRound = round == 1) ?: break
            reply.text.takeIf { it.isNotBlank() }?.let(texts::add)
            if (reply.toolCalls.isEmpty() || tools.isEmpty() || round == MAX_AI_REQUESTS_PER_ANSWER) break
            messages += AiWireMessage(AiRole.ASSISTANT, reply.text, toolCalls = reply.toolCalls)
            reply.toolCalls.forEach { call -> messages += AiWireMessage(AiRole.TOOL, handle(call), toolCallId = call.id) }
        }
        // Only actions the app does not offer, or that were all refused, and no words: nothing to show.
        if (texts.isEmpty() && records.isEmpty()) throw AiChatException(AiErrorKind.BAD_REPLY, "The reply had no text.")
    }

    /** Null when an answer after actions came back empty: the actions are the answer. */
    private suspend fun request(
        messages: List<AiWireMessage>,
        firstRound: Boolean,
    ): AiReply? =
        try {
            ask(messages, tools)
        } catch (error: AiChatException) {
            when {
                error.kind == AiErrorKind.UNSUPPORTED_TOOLS && firstRound && tools.isNotEmpty() -> {
                    toolsRefused = true
                    tools = emptyList()
                    ask(messages, tools)
                }
                error.kind == AiErrorKind.BAD_REPLY && records.isNotEmpty() -> null
                else -> throw error
            }
        }

    /** Runs or holds one call and returns what the model reads about it. */
    private suspend fun handle(call: AiToolCall): String {
        val app = app ?: return "Not done: actions are not available here."
        val action =
            when (val parsed = parseAiAction(call, memories)) {
                is AiActionParse.Invalid -> return "Not done: ${parsed.reason}"
                is AiActionParse.Valid -> parsed.action
            }
        if (action is AiAction.LookAtVideo) {
            otherText = true
            record(action, app.describe(action), AiActionState.DONE)
            return app.lookAtVideo(action.linesAround)
        }
        refusal(app, action)?.let { return "Not done: $it" }
        changes++
        val text = app.describe(action)
        if (action.alwaysAsks || (otherText && action.asksAfterOtherText)) {
            waiting[record(action, text, AiActionState.WAITING).id] = action
            return "Not done yet: the user sees a \"${text.button}\" button for this and decides. Tell them to tap it if they want it."
        }
        return when (val outcome = app.perform(action)) {
            is AiActionOutcome.Refused -> "Not done: ${outcome.reason}"
            is AiActionOutcome.Done -> {
                val undoable = outcome.undo.isNotEmpty()
                val record = record(action, AiActionText(outcome.label), AiActionState.DONE, outcome.note, undoable)
                if (undoable) undos[record.id] = outcome.undo
                "Done: ${outcome.note}." + if (undoable) " The user can undo it." else ""
            }
        }
    }

    private fun refusal(
        app: AiAppActions,
        action: AiAction,
    ): String? =
        when {
            changes >= MAX_AI_ACTIONS_PER_ANSWER ->
                "at most $MAX_AI_ACTIONS_PER_ANSWER actions run for one question. Tell the user what is left to do."
            action is AiAction.OpenVideo && history.none { it.role == AiRole.USER && action.videoId in it.text } ->
                "only a YouTube link the user typed in this chat can be opened. Offer a YouTube search instead."
            else -> app.refusal(action)
        }

    private fun record(
        action: AiAction,
        text: AiActionText,
        state: AiActionState,
        note: String = aiActionNote(action),
        undoable: Boolean = false,
    ): AiActionRecord = AiActionRecord(newId(), action.kind, text.label, note, state, text.button, undoable).also(records::add)
}
