package com.fskeyboard.ime.lingxi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalNameTest {
    @Test
    fun namesMatchAndOrdinaryWordsDoNot() {
        val names = listOf(
            "蔡英文", "馬英九", "陳水扁", "柯文哲", "韓國瑜", "賴清德", "王小明",
            "諸葛亮", "沈伯洋", "習近平", "馬斯克", "陳其邁", "歐陽娜娜",
        )
        for (name in names) assertTrue(name, isPersonalName(name))
        val ordinary = listOf(
            "董事長", "紀錄片", "高雄市", "高中生", "馬拉松", "游泳池", "經濟部",
            "國民黨", "高科技", "許多人", "簡單的", "馬上來", "王子麵", "黃金週",
            "白皮書", "白頭翁", "台灣", "黃金", "馬上", "高中", "方向", "王子",
            "週末", "白天", "林口", "我們", "你好", "你們", "高高興興", "歐洲國家",
            "馬賽克", "杜鵑花", "吳先生", "司馬遷",
        )
        for (word in ordinary) assertFalse(word, isPersonalName(word))
    }

    @Test
    fun demoteLowersANameAndKeepsACommonSlice() {
        val bytes = encodeLexicon(
            listOf("ㄚ"),
            listOf(
                LexiconRow(intArrayOf(0, 0, 0), "馬英九", 1f, 0),
                LexiconRow(intArrayOf(0, 0, 0), "菜單", 0.2f, 0),
                LexiconRow(intArrayOf(0, 0, 0), "蔡英文", 1f, 0),
                LexiconRow(intArrayOf(0, 0, 0), "賴清德", 1e-5f, 0),
            ),
        )
        val lexicon = decodeLexicon(bytes)
        val changed = lexicon.demotePersonalNames { text ->
            when (text) {
                "英文" -> 3e-4
                "賴清德" -> 2e-4
                else -> 0.0
            }
        }
        assertEquals(1, changed)
        val rows = lexicon.exact(listOf("ㄚ", "ㄚ", "ㄚ"))
        assertEquals(PERSONAL_NAME_SCALE, rows.single { it.surface == "馬英九" }.prw, 0f)
        assertEquals(0.2f, rows.single { it.surface == "菜單" }.prw, 0f)
        assertEquals(1f, rows.single { it.surface == "蔡英文" }.prw, 0f)
        assertEquals(1e-5f, rows.single { it.surface == "賴清德" }.prw, 0f)
    }

    @Test
    fun demotedNameRanksBehindTheOrdinaryHomophone() {
        val reading = intArrayOf(0, 0, 0)
        val syllables = listOf("ㄚ", "ㄚ", "ㄚ")
        val bytes = encodeLexicon(
            listOf("ㄚ"),
            listOf(
                LexiconRow(reading, "蔡英文", 1f, 0),
                LexiconRow(reading, "菜單", 0.2f, 0),
            ),
        )
        val lexicon = decodeLexicon(bytes)
        val before = LingxiEngine(
            LingxiTables(lexicon, bareLm(), census()),
            null,
        ).select(syllables, "", null, useModel = false)
        assertEquals("蔡英文", before.candidates.first())
        lexicon.demotePersonalNames { 0.0 }
        val after = LingxiEngine(
            LingxiTables(lexicon, bareLm(), census()),
            null,
        ).select(syllables, "", null, useModel = false)
        assertEquals("菜單", after.candidates.first())
        assertTrue(after.candidates.contains("蔡英文"))
    }

    @Test
    fun savedPhraseIsNotDemotedAgain() {
        val base = encodeLexicon(listOf("ㄚ"), listOf(LexiconRow(intArrayOf(0), "啊", 0.4f, 0)))
        val lexicon = decodeLexicon(base)
        lexicon.demotePersonalNames { 0.0 }
        val merged = mergeChiaUnigrams(
            lexicon.encode(),
            listOf(ChiaUnigram(qstring(), "蔡英文", kotlin.math.ln(0.5))),
            lexicon.supplementMask,
        )
        val after = decodeLexicon(merged.bytes, merged.supplementMask)
        val name = after.exact(listOf("ㄘㄞˋ", "ㄧㄥ", "ㄨㄣˊ")).single()
        assertEquals("蔡英文", name.surface)
        assertEquals(0.5f, name.prw, 1e-6f)
    }

    private fun qstring(): String {
        val syllables = listOf("ㄘㄞˋ", "ㄧㄥ", "ㄨㄣˊ")
        return com.fskeyboard.ime.candidate.BopomofoOrder.qstring(syllables) ?: error("qstring")
    }

    private fun bareLm(): LingxiLm {
        return LingxiLm(byteArrayOf(), intArrayOf(0), floatArrayOf(), intArrayOf(0), byteArrayOf(), byteArrayOf())
    }

    private fun census() = LingxiCensus(1, 1, 1, 1, 1, 1)
}
