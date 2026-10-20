package com.kienhoang.dualsubreplay.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiActionsTest {
    private fun call(
        name: String,
        arguments: String,
    ) = AiToolCall("c", name, arguments, JSONObject().put("id", "c").toString())

    private fun parsed(
        name: String,
        arguments: String,
    ): AiAction = (parseAiAction(call(name, arguments)) as AiActionParse.Valid).action

    private fun refused(
        name: String,
        arguments: String,
    ): String = (parseAiAction(call(name, arguments)) as AiActionParse.Invalid).reason

    @Test
    fun settingValuesAreCheckedAndWrittenOneWay() {
        assertEquals(
            AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "150"),
            parsed(AI_SETTING_TOOL, """{"setting":"text_size","value":"150%"}"""),
        )
        assertEquals(AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "150"), parsed(AI_SETTING_TOOL, """{"setting":"text_size","value":1.5}"""))
        assertEquals(
            AiAction.ChangeSetting(AiSetting.PLAYBACK_SPEED, "0.75"),
            parsed(AI_SETTING_TOOL, """{"setting":"playback_speed","value":"0.75x"}"""),
        )
        assertEquals(
            AiAction.ChangeSetting(AiSetting.PLAYBACK_SPEED, "0.5"),
            parsed(AI_SETTING_TOOL, """{"setting":"playback_speed","value":"50%"}"""),
        )
        assertEquals(
            AiAction.ChangeSetting(AiSetting.WORD_LEARNING_MODE, "off"),
            parsed(AI_SETTING_TOOL, """{"setting":"word_learning_mode","value":false}"""),
        )
        assertEquals(
            AiAction.ChangeSetting(AiSetting.ORIGINAL_CAPTIONS, "paused"),
            parsed(AI_SETTING_TOOL, """{"setting":"original_captions","value":"Only when paused"}"""),
        )
        assertEquals(
            AiAction.ChangeSetting(AiSetting.HIGHLIGHT_COLOR, "amber"),
            parsed(AI_SETTING_TOOL, """{"setting":"spoken_word_highlight_color","value":"yellow"}"""),
        )
        assertEquals(
            AiAction.ChangeSetting(AiSetting.DEFAULT_VIEW, "overlay"),
            parsed(AI_SETTING_TOOL, """{"setting":"DEFAULT_VIEW","value":"Scroll-friendly overlay"}"""),
        )
    }

    private fun setting(
        key: String,
        value: String,
    ): String? =
        (parseAiAction(call(AI_SETTING_TOOL, JSONObject().put("setting", key).put("value", value).toString())) as? AiActionParse.Valid)
            ?.let { (it.action as AiAction.ChangeSetting).value }

    @Test
    fun theNewSettingsTakeTheirDocumentedValues() {
        assertEquals("above", setting("translation_position", "Above"))
        assertEquals("auto", setting("google_wait", "Automatic"))
        assertEquals("4", setting("google_wait", "4 seconds"))
        assertEquals("8", setting("google_wait", "8s"))
        assertEquals(null, setting("google_wait", "3"))
        assertEquals("below", setting("translation_position", "under"))
        assertEquals("-10", setting("portrait_panel_position", "-10%"))
        assertEquals("0", setting("portrait_panel_position", "0"))
        assertEquals(null, setting("portrait_panel_position", "25"))
        assertEquals("125", setting("pronunciation_volume", "130"))
        assertEquals("150", setting("pronunciation_volume", "1.5"))
        assertEquals(null, setting("pronunciation_volume", "300"))
        assertEquals("light", setting("app_colors", "Light"))
        assertEquals("light", setting("app_colors", "white"))
        assertEquals("forest", setting("app_colors", "green"))
        assertEquals("deep_teal", setting("overlay_background", "teal"))
        assertEquals("blue", setting("accent_color", "Blue"))
        assertEquals("cyan", setting("accent_color", "teal"))
        assertEquals("both", setting("colored_subtitle_lines", "Both"))
        assertEquals("off", setting("remember_dragged_position", "false"))
        assertEquals("vi", setting("app_language", "Vietnamese"))
        assertEquals("pt-BR", setting("app_language", "Portuguese"))
        assertEquals("zh-CN", setting("app_language", "zh"))
        assertEquals("device", setting("app_language", "follow the device"))
        assertEquals(null, setting("app_language", "Klingon"))
        assertEquals("ja", setting("original_caption_language", "Japanese"))
        assertEquals("auto", setting("original_caption_language", "Auto"))
        assertEquals("15", setting("daily_goal", "15 minutes"))
        assertEquals("off", setting("daily_goal", "0"))
        assertEquals(null, setting("daily_goal", "7"))
    }

    @Test
    fun theStudySettingsTakeTheirDocumentedValues() {
        assertEquals("listen_first", setting("study_play_mode", "Listen first"))
        assertEquals("pause_after_line", setting("study_play_mode", "pause after each line"))
        assertEquals("normal", setting("study_play_mode", "off"))
        assertEquals(null, setting("study_play_mode", "shadowing"))
        assertEquals("3", setting("study_repeat_count", "3 times"))
        assertEquals(null, setting("study_repeat_count", "4"))
        assertEquals("2x", setting("skip_silence", "2"))
        assertEquals("jump", setting("skip_silence", "skip"))
        assertEquals("off", setting("skip_silence", "Off"))
        assertEquals("on", setting("blur_box", "true"))
        assertEquals("off", setting("send_to_ankidroid", "off"))
        assertTrue(AiAction.ChangeSetting(AiSetting.PAGE_TOKEN_CAPTIONS, "on").alwaysAsks)
        assertEquals("auto", setting("page_token_captions", "Automatic"))
        assertEquals("auto", setting("known_words", "on"))
        assertEquals("manual", setting("known_words", "Manual"))
        assertEquals("off", setting("known_words", "off"))
        assertEquals("20", setting("known_words_after", "20 times"))
        assertEquals(null, setting("known_words_after", "7"))
        assertEquals("on", setting("page_token_captions", "always"))
        assertEquals("off", setting("page_token_captions", "false"))
        assertTrue(!AiAction.ChangeSetting(AiSetting.SKIP_SILENCE, "2x").alwaysAsks)
    }

    @Test
    fun settingsThatReloadDownloadOrRebuildAlwaysAsk() {
        val asking =
            listOf(
                AiSetting.NATURAL_SUBTITLE_FLOW,
                AiSetting.PRELOAD_TRANSLATION_MODELS,
                AiSetting.ORIGINAL_CAPTION_LANGUAGE,
                AiSetting.APP_LANGUAGE,
                AiSetting.PAGE_TOKEN_CAPTIONS,
                AiSetting.APP_AUTO_UPDATE,
            )
        assertEquals(asking, AiSetting.entries.filter { it.asks })
        assertTrue(AiAction.ChangeSetting(AiSetting.APP_LANGUAGE, "vi").alwaysAsks)
        assertTrue(!AiAction.ChangeSetting(AiSetting.APP_COLORS, "light").alwaysAsks)
        val description = AI_TOOLS.first { it.name == AI_SETTING_TOOL }.description
        assertTrue(
            "natural_subtitle_flow, preload_translation_models, original_caption_language, app_language, page_token_captions, " +
                "auto_update " +
                "show an Apply button" in
                description,
        )
        assertTrue("app_colors: deep_teal, black, navy, slate, plum, forest or light" in description)
    }

    @Test
    fun settingsOutsideTheListOrRangeAreRefusedWithWhatTheyTake() {
        assertTrue("no setting" in refused(AI_SETTING_TOOL, """{"setting":"api_key","value":"x"}"""))
        assertTrue("80 to 200" in refused(AI_SETTING_TOOL, """{"setting":"text_size","value":"500"}"""))
        assertTrue("0.25 to 2" in refused(AI_SETTING_TOOL, """{"setting":"playback_speed","value":"3x"}"""))
        assertTrue("always, paused or never" in refused(AI_SETTING_TOOL, """{"setting":"translated_captions","value":"sometimes"}"""))
        assertTrue("on or off" in refused(AI_SETTING_TOOL, """{"setting":"landscape_split_view","value":null}"""))
        assertTrue("not a JSON object" in refused(AI_SETTING_TOOL, "text_size=150"))
        assertTrue("no action" in refused("delete_words", "{}"))
    }

    @Test
    fun otherActionsAreCheckedToo() {
        assertEquals(AiAction.LookAtVideo(1), parsed(AI_LOOK_TOOL, """{"lines_around":1}"""))
        assertEquals(AiAction.LookAtVideo(2), parsed(AI_LOOK_TOOL, """{"lines_around":9}"""))
        assertEquals(AiAction.Playback(AiPlayback.REPLAY_PREVIOUS_LINE), parsed(AI_PLAYBACK_TOOL, """{"action":"replay_previous_line"}"""))
        assertTrue("replay_line" in refused(AI_PLAYBACK_TOOL, """{"action":"skip"}"""))
        assertEquals(
            AiAction.SaveWord("勉強", "study", "べんきょう"),
            parsed(AI_SAVE_WORD_TOOL, """{"word":" 勉強 ","meaning":"study","reading":"べんきょう"}"""),
        )
        assertEquals(
            AiAction.SaveWord("勉強", "study", null),
            parsed(AI_SAVE_WORD_TOOL, """{"word":"勉強","meaning":"study","reading":null}"""),
        )
        assertTrue("meaning" in refused(AI_SAVE_WORD_TOOL, """{"word":"勉強"}"""))
        assertEquals(AiAction.SearchYouTube("cooking in Japanese"), parsed(AI_SEARCH_TOOL, """{"query":"cooking in Japanese"}"""))
        assertTrue("query" in refused(AI_SEARCH_TOOL, """{"query":"  "}"""))
        assertEquals(AiAction.OpenVideo("dQw4w9WgXcQ"), parsed(AI_OPEN_VIDEO_TOOL, """{"link":"https://youtu.be/dQw4w9WgXcQ"}"""))
        assertTrue("YouTube" in refused(AI_OPEN_VIDEO_TOOL, """{"link":"https://example.com/watch?v=dQw4w9WgXcQ"}"""))
    }

    @Test
    fun translationChangesTakeACodeOrAnEnglishName() {
        assertEquals(AiAction.ChangeTranslation(null, "vi"), parsed(AI_TRANSLATION_TOOL, """{"target_language":"Vietnamese"}"""))
        assertEquals(
            AiAction.ChangeTranslation(false, "pt"),
            parsed(AI_TRANSLATION_TOOL, """{"google_translate":false,"target_language":"pt-BR"}"""),
        )
        assertEquals(
            AiAction.ChangeTranslation(true, null),
            parsed(AI_TRANSLATION_TOOL, """{"google_translate":true,"target_language":null}"""),
        )
        assertTrue("Klingon" in refused(AI_TRANSLATION_TOOL, """{"target_language":"Klingon"}"""))
        assertTrue("both" in refused(AI_TRANSLATION_TOOL, "{}"))
    }

    @Test
    fun navigationAndTranslationAlwaysAskAndSettingsAskAfterOtherText() {
        assertTrue(AiAction.SearchYouTube("x").alwaysAsks)
        assertTrue(AiAction.OpenVideo("dQw4w9WgXcQ").alwaysAsks)
        assertTrue(AiAction.ChangeTranslation(true, null).alwaysAsks)
        assertTrue(!AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "150").alwaysAsks)
        assertTrue(AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "150").asksAfterOtherText)
        assertTrue(!AiAction.SaveWord("a", "b", null).asksAfterOtherText)
        assertTrue(!AiAction.Playback(AiPlayback.PAUSE).asksAfterOtherText)
        assertTrue(AiAction.SaveMemory("Studies for JLPT N3").asksAfterOtherText)
        assertTrue(AiAction.ForgetMemory(AiMemory("m", "x", 0)).asksAfterOtherText)
        assertTrue(!AiAction.SaveMemory("Studies for JLPT N3").alwaysAsks)
    }

    @Test
    fun memoryCallsTakeOneShortSentenceAndANumberFromTheList() {
        val memories = listOf(AiMemory("m1", "Studies for JLPT N4", 1), AiMemory("m2", "Likes short answers", 2))

        fun parse(
            name: String,
            arguments: String,
        ) = parseAiAction(call(name, arguments), memories)
        assertEquals(
            AiActionParse.Valid(AiAction.SaveMemory("Studies for JLPT N3")),
            parse(AI_SAVE_MEMORY_TOOL, """{"text":" Studies for\nJLPT N3 "}"""),
        )
        assertEquals(
            AiActionParse.Valid(AiAction.SaveMemory("Studies for JLPT N3", memories[0])),
            parse(AI_SAVE_MEMORY_TOOL, """{"text":"Studies for JLPT N3","replaces":"1"}"""),
        )
        assertEquals(AiActionParse.Valid(AiAction.ForgetMemory(memories[1])), parse(AI_FORGET_MEMORY_TOOL, """{"number":2.0}"""))
        assertEquals(
            AiActionParse.Invalid("Give the memory as one short sentence of at most 200 characters."),
            parse(AI_SAVE_MEMORY_TOOL, """{"text":"${"a".repeat(201)}"}"""),
        )
        assertEquals(AiActionParse.Invalid("Give the number of a saved memory, 1 to 2."), parse(AI_FORGET_MEMORY_TOOL, """{"number":0}"""))
        assertEquals(
            AiActionParse.Invalid("Give the number of a saved memory, 1 to 2."),
            parse(AI_SAVE_MEMORY_TOOL, """{"text":"x","replaces":1.5}"""),
        )
        assertEquals("There are no saved memories.", refused(AI_FORGET_MEMORY_TOOL, """{"number":1}"""))
        assertEquals(AiActionKind.MEMORY, AiAction.RestoreMemory(memories[0]).kind)
        assertEquals(
            "Save the memory \"Studies for JLPT N3\" in place of \"Studies for JLPT N4\"",
            aiActionNote(AiAction.SaveMemory("Studies for JLPT N3", memories[0])),
        )
    }

    @Test
    fun laterQuestionsSeeWhatHappenedToEachAction() {
        val records =
            listOf(
                AiActionRecord("1", AiActionKind.SETTING, "Text size: 150%", "Set text_size to 150", AiActionState.UNDONE),
                AiActionRecord("2", AiActionKind.VIDEO, "Search", "Search YouTube for \"cats\"", AiActionState.WAITING, "Search"),
                AiActionRecord("3", AiActionKind.WORD, "Saved", "Saved \"猫\"", AiActionState.DONE),
            )
        assertEquals(
            "[App actions with this answer: Set text_size to 150 (the user undid it); " +
                "Search YouTube for \"cats\" (shown as a button; not done yet); Saved \"猫\"]",
            aiActionsNote(records),
        )
        val answer = AiChatMessage("a", AiRole.ASSISTANT, "Done!", 0, actions = records.take(1))
        assertEquals("Done!\n\n[App actions with this answer: Set text_size to 150 (the user undid it)]", answer.wireContent())
    }

    @Test
    fun fullAccessActionsAreCheckedAndNeverShowAKey() {
        assertEquals(
            AiAction.ChangeAiSetting(AiOwnSetting.SERVICE, "opencode_zen"),
            parsed(AI_CHANGE_AI_SETTING_TOOL, """{"setting":"ai_service","value":"OpenCode Zen"}"""),
        )
        assertEquals(
            AiAction.ChangeAiSetting(AiOwnSetting.SERVICE, "claude"),
            parsed(AI_CHANGE_AI_SETTING_TOOL, """{"setting":"service","value":"Anthropic"}"""),
        )
        assertEquals(
            AiAction.ChangeAiSetting(AiOwnSetting.CHAT_HISTORY, "30_days"),
            parsed(AI_CHANGE_AI_SETTING_TOOL, """{"setting":"ai_chat_history","value":"30 days"}"""),
        )
        assertEquals(
            AiAction.ChangeAiSetting(AiOwnSetting.MODEL, "gemini-flash-latest"),
            parsed(AI_CHANGE_AI_SETTING_TOOL, """{"setting":"ai_model","value":" gemini-flash-latest "}"""),
        )
        assertTrue(refused(AI_CHANGE_AI_SETTING_TOOL, """{"setting":"ai_model","value":"two words"}""").startsWith("ai_model takes"))
        assertTrue(refused(AI_CHANGE_AI_SETTING_TOOL, """{"setting":"ai_enabled","value":"off"}""").startsWith("setting must be"))
        assertEquals(
            AiAction.RemoveApiKey(AiProvider.GEMINI, "fpzA"),
            parsed(AI_REMOVE_KEY_TOOL, """{"service":"Google Gemini","ending":"••••fpzA"}"""),
        )
        assertEquals(AiAction.Reset(AiResetTarget.SUBTITLE_POSITIONS), parsed(AI_RESET_TOOL, """{"what":"subtitle positions"}"""))
        assertEquals(AiAction.EditSavedWord("猫", "cat", null), parsed(AI_EDIT_WORD_TOOL, """{"word":"猫","meaning":"cat"}"""))
        assertTrue(refused(AI_EDIT_WORD_TOOL, """{"word":"猫"}""").startsWith("Give a new meaning"))
        val key = "sk-or-v1-FAKE-key-for-unit-tests-9xQe"
        val add = parsed(AI_ADD_KEY_TOOL, """{"key":"$key"}""")
        assertEquals(AiAction.AddApiKey(key), add)
        assertEquals("Add an API key ending in 9xQe", aiActionNote(add))
        assertTrue(key !in add.toString())
        assertTrue(refused(AI_ADD_KEY_TOOL, """{"key":"short"}""").startsWith("Give the whole key"))
    }
}
