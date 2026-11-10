package com.fskeyboard.ime.candidate

import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.ZhuyinTone
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthBandTest {
    @Test
    fun zeroSyllablesKeepsMixedExtraAndACreatedRow() {
        val created = row("自", span = 1, extra = 2, reading = "自")
        val early = row("乙", span = 1, extra = 5, reading = "乙")
        val late = row("甲", span = 3, extra = 0, reading = "甲")
        val preference = CandidatePreference()
        preference.remember(created)
        val input = listOf(early, created, late)
        assertEquals(input, LengthBand.order(input, syllables = 0, preference::isCreated))
    }

    @Test
    fun oneSyllableGroupsByExtraAndPinsCreatedWithoutASentenceBand() {
        val zhu = row("主", span = 1, extra = 0)
        val zhong = row("中", span = 1, extra = 1)
        val created = row("注", span = 1, extra = 1)
        val long = row("中華", span = 2, extra = 3)
        val preference = CandidatePreference()
        preference.remember(created)
        val input = listOf(zhong, long, created, zhu)
        val ordered = LengthBand.order(input, syllables = 1, preference::isCreated)
        assertEquals(listOf("主", "注", "中", "中華"), ordered.map { it.text })
    }

    @Test
    fun niHaoStaysAheadOfNiWhenTheCharacterHasSixtyFourSteps() {
        val lexicon = PartialLexicon.index(
            listOf(
                UnigramRow.of("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -1.2),
                UnigramRow.of("你", listOf("ㄋㄧˇ"), -0.4),
            ),
        )
        val found = lexicon.candidates(typed("ㄋㄧ", "ㄏㄠ"))
        val ni = found.first { it.text == "你" }
        val preference = CandidatePreference()
        repeat(64) { preference.promote(ni) }
        val ordered = LengthBand.order(preference.apply(found), 2, preference::isCreated)
        assertTrue(ordered.indexOfFirst { it.text == "你好" } < ordered.indexOfFirst { it.text == "你" })
    }

    @Test
    fun aCharacterWithSixtyFourStepsStaysBehindTheWordAndTheSentence() {
        val lexicon = PartialLexicon.index(
            listOf(
                UnigramRow.of("你好嗎啊", listOf("ㄋㄧˇ", "ㄏㄠˇ", "ㄇㄚ", "ㄚ"), -0.2),
                UnigramRow.of("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -0.4),
                UnigramRow.of("你", listOf("ㄋㄧˇ"), -1.0),
            ),
        )
        val found = lexicon.candidates(typed("ㄋㄧ", "ㄏㄠ", "ㄇㄚ", "ㄚ"))
        val ni = found.first { it.text == "你" }
        val preference = CandidatePreference()
        repeat(64) { preference.promote(ni) }
        val applied = preference.apply(found)
        assertEquals("你", applied.first().text)
        val ordered = LengthBand.order(applied, 4, preference::isCreated)
        val texts = ordered.map { it.text }
        assertTrue(texts.indexOf("你好嗎啊") < texts.indexOf("你好"))
        assertTrue(texts.indexOf("你好") < texts.indexOf("你"))
    }

    @Test
    fun aCreatedRowStaysAheadOfTheSameSpanWithSixtyFourSteps() {
        val created = row("自訂", span = 2, extra = 0, reading = "ㄋㄧㄏㄠ")
        val lexicon = row("詞庫", span = 2, extra = 0, reading = "ㄋㄧㄏㄠ")
        val preference = CandidatePreference()
        preference.remember(created)
        repeat(64) { preference.promote(lexicon) }
        val ordered = LengthBand.order(
            preference.apply(listOf(lexicon, created)),
            syllables = 2,
            preference::isCreated,
        )
        assertEquals(listOf("自訂", "詞庫"), ordered.map { it.text })
    }

    @Test
    fun publishPinsTheCreatedRowAfterPreference() {
        val created = row("自訂", span = 2, extra = 0, reading = "ㄋㄧㄏㄠ")
        val lexicon = row("詞庫", span = 2, extra = 0, reading = "ㄋㄧㄏㄠ")
        val preference = CandidatePreference()
        preference.remember(created)
        repeat(64) { preference.promote(lexicon) }
        val shown = mutableListOf<List<Candidate>>()
        val presenter = CandidatePresenter(
            pipeline = CandidatePipeline(),
            preference = preference,
            post = { it.run() },
            closed = { false },
            contextBefore = { "" },
            dropUnusedHabits = { false },
            savePreference = {},
            onPresented = { shown += it },
        )
        presenter.publish(
            EngineSnapshot(
                preedit = "ㄋㄧㄏㄠ",
                composingPreview = "ㄋㄧㄏㄠ",
                candidates = listOf(lexicon, created),
                readingSyllables = 2,
            ),
            KeyboardMode.ZHUYIN,
            KeyboardMode.ZHUYIN,
        )
        assertEquals(listOf("自訂", "詞庫"), shown.last().map { it.text })
    }

    @Test
    fun zhuStaysAheadOfZhongWhenThePenaltyWouldLose() {
        val lexicon = PartialLexicon.index(
            listOf(
                UnigramRow.of("主", listOf("ㄓㄨˇ"), -1.0),
                UnigramRow.of("中", listOf("ㄓㄨㄥ"), 10.0),
            ),
        )
        val found = lexicon.candidates(typed("ㄓㄨ"))
        assertEquals(0, found.first { it.text == "主" }.extra)
        assertEquals(1, found.first { it.text == "中" }.extra)
        assertTrue(found.indexOfFirst { it.text == "中" } < found.indexOfFirst { it.text == "主" })
        val zhong = found.first { it.text == "中" }
        val preference = CandidatePreference()
        repeat(64) { preference.promote(zhong) }
        val applied = preference.apply(found)
        assertTrue(applied.indexOfFirst { it.text == "中" } < applied.indexOfFirst { it.text == "主" })
        val ordered = LengthBand.order(applied, 1, preference::isCreated)
        assertTrue(ordered.indexOfFirst { it.text == "主" } < ordered.indexOfFirst { it.text == "中" })
    }

    @Test
    fun anExactInitialReadingStaysAheadWhenThePenaltyWouldLose() {
        val lexicon = PartialLexicon.index(
            listOf(
                UnigramRow.of("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), 10.0),
                UnigramRow.of("呢呵", listOf("ㄋ", "ㄏ"), -3.0),
            ),
        )
        val found = lexicon.candidates(typed("ㄋ", "ㄏ"))
        assertEquals(0, found.first { it.text == "呢呵" }.extra)
        assertEquals(2, found.first { it.text == "你好" }.extra)
        assertTrue(found.indexOfFirst { it.text == "你好" } < found.indexOfFirst { it.text == "呢呵" })
        val ordered = LengthBand.order(found, 2) { false }
        assertTrue(ordered.indexOfFirst { it.text == "呢呵" } < ordered.indexOfFirst { it.text == "你好" })
    }

    private fun typed(vararg bodies: String): List<SyllableReading> {
        return bodies.map { SyllableReading(it, ZhuyinTone.NONE) }
    }

    private fun row(
        text: String,
        span: Int,
        extra: Int,
        reading: String = "ㄓㄨ",
    ): Candidate {
        return Candidate(
            text = text,
            engineIndex = -1,
            source = CandidateSource.SYNTHETIC,
            span = span,
            reading = reading,
            extra = extra,
        )
    }
}
