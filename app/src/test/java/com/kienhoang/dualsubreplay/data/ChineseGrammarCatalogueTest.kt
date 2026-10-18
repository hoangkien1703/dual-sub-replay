package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseGrammarCatalogueTest {
    private companion object {
        val dictionary by lazy {
            ChineseDictionary.load(LearnerData.open("zh-words.txt"), LearnerData.open("zh-pinyin.txt"), LearnerData.open("zh-t2s.txt"))
        }

        /** One sentence per point (catalogue and rules), run through the real analyzer: each must find its point. */
        val EXAMPLES: List<Pair<GrammarMeaning, String>> =
            listOf(
                GrammarMeaning.ZH_BUDEBU to "我不得不走了。",
                GrammarMeaning.ZH_BUDAN to "她不但聪明，而且很努力。",
                GrammarMeaning.ZH_WULUN to "不管多忙，他都来。",
                GrammarMeaning.ZH_BURU to "坐车不如走路。",
                GrammarMeaning.ZH_BUYONG to "你不用担心。",
                GrammarMeaning.ZH_BIE to "别说话！",
                GrammarMeaning.ZH_DANSHI to "天气很好，但是有点儿冷。",
                GrammarMeaning.ZH_SUIRAN to "虽然很累，我还是很高兴。",
                GrammarMeaning.ZH_YINWEI_SUOYI to "因为下雨，所以我没去。",
                GrammarMeaning.ZH_RUGUO to "如果明天下雨，我就不去了。",
                GrammarMeaning.ZH_ZHIYAO to "只要努力，就能成功。",
                GrammarMeaning.ZH_ZHIYOU to "只有努力，才能成功。",
                GrammarMeaning.ZH_JIRAN to "既然你来了，就坐吧。",
                GrammarMeaning.ZH_JISHI to "即使下雨，我也去。",
                GrammarMeaning.ZH_LIAN to "他连饭都没吃。",
                GrammarMeaning.ZH_CHULE to "除了他以外，我们都去了。",
                GrammarMeaning.ZH_DUI_LAISHUO to "对我来说，这很重要。",
                GrammarMeaning.ZH_ERSHI to "他不是老师，而是学生。",
                GrammarMeaning.ZH_KANQILAI to "他看起来很累。",
                GrammarMeaning.ZH_YOUDIANR to "这件衣服有点儿贵。",
                GrammarMeaning.ZH_YIDIANR to "便宜一点儿吧。",
                GrammarMeaning.ZH_YUELAIYUE to "天气越来越冷了。",
                GrammarMeaning.ZH_YUE_YUE to "他越说越高兴。",
                GrammarMeaning.ZH_YIBIAN to "我们一边吃饭一边看电视。",
                GrammarMeaning.ZH_KUAIYAO to "火车快要开了。",
                GrammarMeaning.ZH_ZAI_PROGRESSIVE to "他在看电视。",
                GrammarMeaning.ZH_YIXIA to "请等一下。",
                GrammarMeaning.ZH_DIRECTION to "他走进来了。",
                GrammarMeaning.ZH_POTENTIAL to "我听不懂。",
                GrammarMeaning.ZH_RESULT to "我听懂了。",
                GrammarMeaning.ZH_RANG to "老师让我们回家。",
                GrammarMeaning.ZH_YI_WEI to "我们以学习为主。",
                GrammarMeaning.ZH_MA to "你是学生吗？",
                GrammarMeaning.ZH_NE to "我很好，你呢？",
                GrammarMeaning.ZH_SHI to "我是学生。",
                GrammarMeaning.ZH_YOU to "我有一个哥哥。",
                GrammarMeaning.ZH_ZAI to "我在家。",
                GrammarMeaning.ZH_HEN to "她很漂亮。",
                GrammarMeaning.ZH_TAI to "这个太贵了。",
                GrammarMeaning.ZH_DOU to "我们都是学生。",
                GrammarMeaning.ZH_YE to "我也喜欢。",
                GrammarMeaning.ZH_HE to "我和妈妈去商店。",
                GrammarMeaning.ZH_QUESTION_WORD to "你叫什么名字？",
                GrammarMeaning.ZH_DE_SHIHOU to "我小的时候住在北京。",
                GrammarMeaning.ZH_LOCATION to "书在桌子上。",
                GrammarMeaning.ZH_QING to "请喝茶。",
                GrammarMeaning.ZH_HUI to "我会说中文。",
                GrammarMeaning.ZH_NENG to "你能帮我吗？",
                GrammarMeaning.ZH_XIANG to "我想去中国。",
                GrammarMeaning.ZH_DE to "这是我的书。",
                GrammarMeaning.ZH_LE_COMPLETED to "我买了一本书。",
                GrammarMeaning.ZH_GUO to "你吃过北京烤鸭吗？",
                GrammarMeaning.ZH_ZHE to "他穿着一件红衣服。",
                GrammarMeaning.ZH_DE_DEGREE to "他跑得很快。",
                GrammarMeaning.ZH_BI to "他比我高。",
                GrammarMeaning.ZH_ZUI to "这是最好的办法。",
                GrammarMeaning.ZH_JIU to "我马上就来。",
                GrammarMeaning.ZH_YIJING to "他已经走了。",
                GrammarMeaning.ZH_GEI to "我给你打电话。",
                GrammarMeaning.ZH_DUI to "他对我很好。",
                GrammarMeaning.ZH_CONG to "我从北京来。",
                GrammarMeaning.ZH_LI to "我家离学校很近。",
                GrammarMeaning.ZH_WANG to "往前走。",
                GrammarMeaning.ZH_YIQI to "我们一起去吧。",
                GrammarMeaning.ZH_HAI to "他还在睡觉。",
                GrammarMeaning.ZH_ZAI_AGAIN to "我们明天再去。",
                GrammarMeaning.ZH_DEGREE to "这个电影非常有意思。",
                GrammarMeaning.ZH_KEYI to "我可以进来吗？",
                GrammarMeaning.ZH_YAO to "我要一杯咖啡。",
                GrammarMeaning.ZH_BA_PARTICLE to "我们走吧。",
                GrammarMeaning.ZH_FREQUENCY to "我常常去图书馆。",
                GrammarMeaning.ZH_BEFORE_AFTER to "吃饭以前要洗手。",
                GrammarMeaning.ZH_ORDINAL to "这是我第一次来中国。",
                GrammarMeaning.ZH_MEI_EVERY to "我每天都跑步。",
                GrammarMeaning.ZH_LE_CHANGE to "下雨了。",
                GrammarMeaning.ZH_V_NOT_V to "你去不去？",
                GrammarMeaning.ZH_REDUPLICATION to "我看看。",
                GrammarMeaning.ZH_BA to "我把作业做完了。",
                GrammarMeaning.ZH_BEI to "他被老师批评了。",
                GrammarMeaning.ZH_DE_ADVERBIAL to "他高兴地笑了。",
                GrammarMeaning.ZH_GENG to "我比他更快。",
                GrammarMeaning.ZH_XIAN to "我先洗澡，然后睡觉。",
                GrammarMeaning.ZH_RANHOU to "我先洗澡，然后睡觉。",
                GrammarMeaning.ZH_HAISHI to "你喝茶还是喝咖啡？",
                GrammarMeaning.ZH_HUOZHE to "我们可以坐公交车或者打车。",
                GrammarMeaning.ZH_CAI to "他十点才起床。",
                GrammarMeaning.ZH_YOU_AGAIN to "他又迟到了。",
                GrammarMeaning.ZH_BIJIAO to "今天比较冷。",
                GrammarMeaning.ZH_ZHI to "我只喝水。",
                GrammarMeaning.ZH_YIDING to "你一定要来。",
                GrammarMeaning.ZH_ZHONGYU to "我终于找到了。",
                GrammarMeaning.ZH_MASHANG to "我马上回来。",
                GrammarMeaning.ZH_YIZHI to "他一直在学习。",
                GrammarMeaning.ZH_QISHI to "其实我不喜欢。",
                GrammarMeaning.ZH_GANG to "我刚到。",
                GrammarMeaning.ZH_YINGGAI to "你应该多休息。",
                GrammarMeaning.ZH_BIXU to "我们必须走了。",
                GrammarMeaning.ZH_XUYAO to "我需要你的帮助。",
                GrammarMeaning.ZH_YUANYI to "你愿意帮我吗？",
                GrammarMeaning.ZH_WEILE to "为了学中文，他去了北京。",
                GrammarMeaning.ZH_GUANYU to "这是一本关于历史的书。",
                GrammarMeaning.ZH_GEN to "我跟朋友一起去。",
                GrammarMeaning.ZH_YIYANG to "我跟你一样高。",
                GrammarMeaning.ZH_HAOXIANG to "他好像生病了。",
                GrammarMeaning.ZH_ZHEME to "今天这么冷！",
                GrammarMeaning.ZH_A_PARTICLE to "好漂亮啊！",
                GrammarMeaning.ZH_SHI_DE to "我是昨天来的。",
                GrammarMeaning.ZH_YI_JIU to "我一到家就睡觉了。",
                GrammarMeaning.ZH_YUSHI to "天黑了，于是我们回家了。",
                GrammarMeaning.ZH_DUOME to "这里的风景多么美啊！",
                GrammarMeaning.ZH_YUANLAI to "原来是你！",
                GrammarMeaning.ZH_DAODI to "你到底想说什么？",
                GrammarMeaning.ZH_SHENZHI to "他甚至忘了自己的生日。",
                GrammarMeaning.ZH_FOUZE to "快走吧，否则会迟到。",
                GrammarMeaning.ZH_JINGRAN to "他竟然不知道。",
                GrammarMeaning.ZH_NANDAO to "难道你不知道吗？",
                GrammarMeaning.ZH_JINGUAN to "尽管很难，他还是成功了。",
                GrammarMeaning.ZH_SUIZHE to "随着时间的推移，一切都变了。",
                GrammarMeaning.ZH_FANZHENG to "反正我不去。",
                GrammarMeaning.ZH_BIJING to "他毕竟还是个孩子。",
                GrammarMeaning.ZH_WEIBI to "贵的东西未必好。",
                GrammarMeaning.ZH_JIANZHI to "这简直太好了！",
                GrammarMeaning.ZH_WANYI to "万一下雨怎么办？",
                GrammarMeaning.ZH_ZONGZHI to "总之，我们要努力。",
                GrammarMeaning.ZH_ZHIYU to "至于钱的问题，以后再说。",
                GrammarMeaning.ZH_YUQI to "与其等待，不如行动。",
                GrammarMeaning.ZH_NINGKE to "我宁可走路也不坐车。",
                GrammarMeaning.ZH_HEBI to "何必生气呢？",
                GrammarMeaning.ZH_YIMIAN to "早点出发，以免迟到。",
                GrammarMeaning.ZH_YIBIAN_SO to "请留下电话，以便联系。",
                GrammarMeaning.ZH_CONGER to "他努力学习，从而提高了成绩。",
                GrammarMeaning.ZH_GURAN to "这个办法固然好，但是太贵了。",
                GrammarMeaning.ZH_KUANGQIE to "太晚了，况且我也累了。",
                GrammarMeaning.ZH_MEN to "孩子们在公园里玩。",
                GrammarMeaning.ZH_YONG to "我用筷子吃饭。",
                GrammarMeaning.ZH_WEI_FOR to "我为你感到高兴。",
                GrammarMeaning.ZH_XIANG_TOWARD to "他向我走过来。",
                GrammarMeaning.ZH_YIWEI_THOUGHT to "我以为你走了。",
                GrammarMeaning.ZH_ER to "这个房间小而干净。",
                GrammarMeaning.ZH_BING to "他并不知道这件事。",
                GrammarMeaning.ZH_YOU_BY to "这个问题由他负责。",
                GrammarMeaning.ZH_SUO to "这就是我所知道的。",
                GrammarMeaning.ZH_ZHISHI to "我只是想帮你。",
                GrammarMeaning.ZH_YOUYU to "由于下雨，比赛取消了。",
                GrammarMeaning.ZH_GENJU to "根据天气预报，明天会下雨。",
                GrammarMeaning.ZH_YU_AT to "他毕业于北京大学。",
                GrammarMeaning.ZH_YI_BY to "他以第一名的成绩毕业了。",
                GrammarMeaning.ZH_ZHIYI to "这是世界上最高的山之一。",
                GrammarMeaning.ZH_ZHI_OF to "这是友谊之桥。",
                GrammarMeaning.ZH_DUIYU to "对于这个问题，我有不同的看法。",
                GrammarMeaning.ZH_YINCI to "他生病了，因此没来上课。",
                GrammarMeaning.ZH_RANER to "他很努力，然而没有成功。",
                GrammarMeaning.ZH_CHUFEI to "除非你去，否则我不去。",
                GrammarMeaning.ZH_YAOME to "要么你去，要么我去。",
                GrammarMeaning.ZH_JI_YOU to "她既聪明又漂亮。",
                GrammarMeaning.ZH_TONGSHI to "我们同时到了。",
                GrammarMeaning.ZH_SHIDE to "他像孩子似的笑了。",
                GrammarMeaning.ZH_ZICONG to "自从来到北京，我每天都很忙。",
                GrammarMeaning.ZH_YIJI to "我买了苹果、香蕉以及橙子。",
                GrammarMeaning.ZH_TI to "请替我谢谢他。",
                GrammarMeaning.ZH_MEASURE to "这个苹果很好吃。",
                GrammarMeaning.ZH_BU to "我不喝咖啡。",
                GrammarMeaning.ZH_MEI to "我昨天没去。",
            )

        /** Points found by the rules in ChineseGrammar.kt rather than the catalogue. */
        val RULE_MEANINGS =
            setOf(
                GrammarMeaning.ZH_DE,
                GrammarMeaning.ZH_LE_COMPLETED,
                GrammarMeaning.ZH_LE_CHANGE,
                GrammarMeaning.ZH_V_NOT_V,
                GrammarMeaning.ZH_REDUPLICATION,
                GrammarMeaning.ZH_SHI_DE,
                GrammarMeaning.ZH_YI_JIU,
            )
    }

    private fun morphemes(text: String) = dictionary.segment(text)

    private fun grammar(text: String) = chineseGrammar(morphemes(text))

    @Test
    fun everyPointIsFoundInItsExampleSentence() {
        val missed = EXAMPLES.filter { (meaning, sentence) -> grammar(sentence).none { meaning in it.meanings } }
        val report =
            missed.joinToString("\n") { (meaning, sentence) ->
                "$meaning in $sentence: ${morphemes(
                    sentence,
                ).map { "${it.surface}/${it.pos}/${it.conjugationForm}" }} ${grammar(sentence).map { it.form }}"
            }
        assertTrue("Not found:\n$report", missed.isEmpty())
    }

    @Test
    fun everyCataloguePointHasAnExampleAndItsOwnMeaning() {
        val catalogueMeanings = CHINESE_GRAMMAR_CATALOGUE.flatMap { it.meanings }
        assertEquals(catalogueMeanings.size, catalogueMeanings.toSet().size)
        assertEquals(catalogueMeanings.toSet() + RULE_MEANINGS, EXAMPLES.map { it.first }.toSet())
        assertEquals(EXAMPLES.size, EXAMPLES.map { it.first }.toSet().size)
    }

    @Test
    fun theCatalogueCoversEveryHskLevel() {
        val levels = CHINESE_GRAMMAR_CATALOGUE.map { it.level }.toSet()
        assertEquals(HskLevel.entries.toSet(), levels)
        assertTrue(CHINESE_GRAMMAR_CATALOGUE.size >= 140)
    }

    /** A few points whose HSK 3.0 level differs from the old six-level HSK, as the syllabus lists them. */
    @Test
    fun levelsFollowHsk3() {
        assertEquals(HskLevel.HSK1, levelOf(GrammarMeaning.ZH_YIBIAN))
        assertEquals(HskLevel.HSK2, levelOf(GrammarMeaning.ZH_BUDAN))
        assertEquals(HskLevel.HSK3, levelOf(GrammarMeaning.ZH_BUDEBU))
        assertEquals(HskLevel.HSK7_9, levelOf(GrammarMeaning.ZH_YUQI))
        assertEquals(HskLevel.HSK1, ruleLevel(GrammarMeaning.ZH_LE_CHANGE, "下雨了。"))
        assertEquals(HskLevel.HSK2, ruleLevel(GrammarMeaning.ZH_SHI_DE, "我是昨天来的。"))
        assertEquals(HskLevel.HSK2, ruleLevel(GrammarMeaning.ZH_YI_JIU, "我一到家就睡觉了。"))
    }

    private fun levelOf(meaning: GrammarMeaning) = CHINESE_GRAMMAR_CATALOGUE.single { meaning in it.meanings }.level

    private fun ruleLevel(
        meaning: GrammarMeaning,
        sentence: String,
    ) = grammar(sentence).first { meaning in it.meanings }.level
}
