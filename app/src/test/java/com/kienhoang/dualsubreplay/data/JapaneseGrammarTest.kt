package com.kienhoang.dualsubreplay.data

import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseGrammarTest {
    private companion object {
        val analyzer by lazy { Tokenizer() }
    }

    private fun grammar(text: String) = japaneseGrammar(japaneseMorphemes(analyzer, text))

    /** Each grammar point as its form and likeliest meaning, in sentence order. */
    private fun points(text: String) = grammar(text).map { it.form to it.meanings.first() }

    private fun selected(
        text: String,
        word: String,
    ): List<Pair<String, GrammarMeaning>> {
        val start = text.indexOf(word)
        return grammarForSelection(grammar(text), start, start + word.length).map { it.form to it.meanings.first() }
    }

    @Test
    fun aSelectedNounShowsTheParticleRightAfterIt() {
        // The owner's Renshuu example: tapping 体育館 explains the において after it.
        assertEquals(
            listOf("において" to GrammarMeaning.IN_AT_FORMAL),
            selected("来年は新しい文化祭の体育館において行われる。", "体育館"),
        )
    }

    @Test
    fun aConjugatedVerbListsItsEndingsWithoutRepeatingTheirParts() {
        assertEquals(
            listOf("〜てくれる" to GrammarMeaning.FAVOR_RECEIVE, "〜ました" to GrammarMeaning.POLITE_PAST),
            selected("僕の妻も早く起きて、見送りしてくれました。", "見送りしてくれました"),
        )
    }

    @Test
    fun niPicksItsLikeliestMeaningFromTheWordsAroundIt() {
        assertEquals("に" to GrammarMeaning.DESTINATION, points("日本に行きましょう！").first())
        assertEquals("に" to GrammarMeaning.PURPOSE, points("映画を見に行きたいです。")[1])
        assertEquals("に" to GrammarMeaning.BY_PASSIVE, points("先生に褒められた。").first())
        assertEquals("に" to GrammarMeaning.TIME, points("三時に起きる。").first())
        assertEquals("に" to GrammarMeaning.RECIPIENT, points("友達に会う。").first())
        assertEquals("に" to GrammarMeaning.RECIPIENT, points("母に料理を作ってもらった。").first())
        assertEquals("に" to GrammarMeaning.PLACE_EXIST, points("東京に住んでいます。").first())
    }

    @Test
    fun niKeepsItsOtherMeaningsAsAlternatives() {
        val ni = grammar("体育館に行く").first { it.form == "に" }

        assertEquals(GrammarMeaning.DESTINATION, ni.meanings.first())
        assertTrue(GrammarMeaning.PLACE_EXIST in ni.meanings)
        assertEquals(ni.meanings.toSet().size, ni.meanings.size)
    }

    @Test
    fun politeEndingsAreRecognized() {
        assertEquals(
            listOf("に" to GrammarMeaning.DESTINATION, "〜ましょう" to GrammarMeaning.LETS_POLITE),
            points("日本に行きましょう！"),
        )
        assertEquals(listOf("〜ませんでした" to GrammarMeaning.POLITE_NEGATIVE_PAST), points("食べませんでした。"))
        assertEquals(
            listOf("に" to GrammarMeaning.PLACE_EXIST, "〜ている" to GrammarMeaning.PROGRESSIVE, "〜ます" to GrammarMeaning.POLITE),
            points("東京に住んでいます。"),
        )
    }

    @Test
    fun obligationPermissionAndProhibitionAreWholePatterns() {
        assertEquals(listOf("〜なければならない" to GrammarMeaning.MUST), points("食べなければならない。"))
        assertEquals(
            listOf("で" to GrammarMeaning.PLACE_OF_ACTION, "を" to GrammarMeaning.OBJECT, "〜てはいけない" to GrammarMeaning.MUST_NOT),
            points("ここで写真を撮ってはいけません。"),
        )
        assertEquals(
            listOf(
                "を" to GrammarMeaning.OBJECT,
                "〜てもいい" to GrammarMeaning.MAY,
                "です" to GrammarMeaning.COPULA_POLITE,
                "か" to GrammarMeaning.QUESTION,
            ),
            points("窓を開けてもいいですか。"),
        )
        assertEquals(listOf("〜なきゃ" to GrammarMeaning.MUST), points("行かなきゃ。"))
        assertEquals(listOf("〜てはいけない" to GrammarMeaning.MUST_NOT), points("食べちゃいけない。"))
    }

    @Test
    fun abilityConditionsAndWishes() {
        assertEquals(
            listOf("を" to GrammarMeaning.OBJECT, "〜ことができる" to GrammarMeaning.CAN, "〜ます" to GrammarMeaning.POLITE),
            points("日本語を話すことができます。"),
        )
        assertEquals("〜たら" to GrammarMeaning.IF_WHEN, points("雨が降ったら、家にいます。")[1])
        assertEquals("ば" to GrammarMeaning.IF, points("行けば分かる。").single())
        assertTrue("〜たい" to GrammarMeaning.WANT in points("映画を見に行きたいです。"))
    }

    @Test
    fun verbEndingsAndHelpers() {
        assertEquals(
            listOf("に" to GrammarMeaning.BY_PASSIVE, "〜(ら)れる" to GrammarMeaning.PASSIVE, "〜た" to GrammarMeaning.PAST),
            points("先生に褒められた。"),
        )
        assertEquals(listOf("〜ちゃう" to GrammarMeaning.COMPLETION, "〜た" to GrammarMeaning.PAST), points("食べちゃった。"))
        assertEquals(listOf("〜てる" to GrammarMeaning.PROGRESSIVE), points("見てる。"))
        assertEquals(listOf("〜てください" to GrammarMeaning.PLEASE), points("見てください。"))
        assertTrue("〜(さ)せる" to GrammarMeaning.CAUSATIVE in points("子供に野菜を食べさせる。"))
        assertEquals(listOf("〜(よ)う" to GrammarMeaning.VOLITIONAL), points("もう寝よう。"))
    }

    @Test
    fun particlesThatDependOnTheirNeighbours() {
        assertEquals(
            listOf("〜の" to GrammarMeaning.NOMINALIZER, "が" to GrammarMeaning.OBJECT_OF_FEELING, "です" to GrammarMeaning.COPULA_POLITE),
            points("勉強するのが好きです。"),
        )
        assertEquals("のに" to GrammarMeaning.EVEN_THOUGH, points("雨なのに出かけた。")[1])
        assertEquals("と" to GrammarMeaning.WITH_AND, points("友達と話したり、本を読んだりする。").first())
        assertEquals("と" to GrammarMeaning.QUOTE, points("いいと思う。").first { it.first == "と" })
        assertEquals("から" to GrammarMeaning.FROM, points("東京から大阪まで電車で行く。").first())
        assertEquals(
            listOf("は" to GrammarMeaning.TOPIC, "〜ない" to GrammarMeaning.NEGATIVE, "でしょう" to GrammarMeaning.PROBABLY),
            points("彼は来ないでしょう。"),
        )
        assertEquals(listOf("〜な" to GrammarMeaning.NA_ADJECTIVE), points("静かな部屋"))
    }

    @Test
    fun aSelectionWithoutGrammarFindsNothing() {
        assertTrue(selected("新しい", "新しい").isEmpty())
    }
}
