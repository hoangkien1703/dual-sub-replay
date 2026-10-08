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
}
