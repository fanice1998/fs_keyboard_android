package com.fskeyboard.ime.candidate

import android.content.ContextWrapper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.EngineStatus
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.ZhuyinSpelling
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ChiaKeyTypingTest {
    @Test
    fun su3cl3ShowsNiHaoAndEnterCommitsIt() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, field, composing, states)
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        assertEquals("su3cl3", engine.asciiLog.toString())
        assertEquals("你好", composing.toString())
        assertEquals(listOf("你好", "妳好"), states.last().candidates.candidates.map { it.text })
        controller.onKey(KeyAction.Enter)
        assertEquals(listOf(13), engine.codes)
        assertEquals(1, engine.acks)
        assertEquals("你好", field.toString())
        assertEquals("", composing.toString())
    }

    @Test
    fun spaceMarksLevelToneWithoutSelecting() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = levelToneLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄊㄧㄢ")
        assertEquals("ㄊㄧㄢ", states.last().preview)
        val open = states.last().candidates.candidates.map { it.text }
        assertTrue(open.contains("天"))
        assertTrue(open.contains("甜"))
        controller.onKey(KeyAction.Space)
        assertEquals("", field.toString())
        assertEquals("ㄊㄧㄢ", states.last().preview)
        val marked = states.last().candidates.candidates.map { it.text }
        assertTrue(marked.contains("天"))
        assertFalse(marked.contains("甜"))
        assertFalse(engine.asciiLog.toString().contains(' '))
        type(controller, "ㄚ")
        assertEquals("ㄊㄧㄢㄚ", states.last().preview)
        assertEquals("", field.toString())
    }

    @Test
    fun spaceOnATonedSyllableIsNotSentToTheEngine() {
        val engine = ScriptedEngine()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, StringBuilder(), StringBuilder(), states)
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇ")
        controller.onKey(KeyAction.Space)
        assertEquals("su3", engine.asciiLog.toString())
        assertTrue(states.last().preview.endsWith("ㄋㄧˇ"))
    }

    @Test
    fun spaceWithNoCompositionInsertsASpace() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, field, StringBuilder(), states)
        controller.onStartInput(false)
        controller.onKey(KeyAction.Space)
        assertEquals(" ", field.toString())
        assertEquals("", states.last().preview)
    }

    @Test
    fun spaceAfterATonedPrefixHoldsTheWholeReading() {
        val reading = "ㄋㄧˇㄊㄧㄢ"
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, field, StringBuilder(), states)
        controller.onStartInput(false)
        type(controller, reading)
        controller.onKey(KeyAction.Space)
        assertEquals("", field.toString())
        assertEquals(reading, states.last().preview)
        val log = engine.asciiLog.toString()
        assertFalse(log.contains(' '))
        assertFalse(log.endsWith("su3"))
        // 平聲 has no glyph, and backspace clears it before a symbol.
        repeat(reading.length + 1) {
            controller.onKey(KeyAction.Backspace)
            assertFalse(states.last().preview.contains("su3"))
        }
        assertEquals("", states.last().preview)
        assertEquals("", field.toString())
    }

    @Test
    fun spaceAfterAnAnsweredToneHoldsTheWholeReading() {
        val reading = "ㄋㄧˇㄊㄧㄢ"
        val engine = ScriptedEngine(answered = setOf("su3"))
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, field, StringBuilder(), states)
        controller.onStartInput(false)
        type(controller, reading)
        controller.onKey(KeyAction.Space)
        assertEquals("", field.toString())
        assertEquals(reading, states.last().preview)
        val log = engine.asciiLog.toString()
        assertFalse(log.contains(' '))
        assertFalse(log.endsWith("su3"))
    }

    @Test
    fun spaceDropsAPredictionStuckOnHeldBopomofo() {
        val reading = "ㄋㄧㄏㄠ"
        val engine = ScriptedEngine(stickyComposing = "預測")
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            composing,
            states,
            partial = sampleLexicon(),
        )
        controller.onStartInput(false)
        type(controller, reading)
        controller.onKey(KeyAction.Space)
        assertEquals("", field.toString())
        assertEquals(reading, states.last().preview)
        assertEquals(reading, composing.toString())
        assertFalse(states.last().preview.contains("預測"))
        assertFalse(states.last().candidates.candidates.any { it.text == "預測" })
        type(controller, "ㄉ")
        assertEquals("", field.toString())
        assertEquals(reading + "ㄉ", states.last().preview)
        assertFalse(states.last().preview.startsWith("預測"))
    }

    @Test
    fun backspaceDeletesInsideTheEngine() {
        val engine = ScriptedEngine()
        val composing = StringBuilder()
        val controller = chiakeyController(engine, StringBuilder(), composing, mutableListOf())
        controller.onStartInput(false)
        type(controller, "ㄋㄧ")
        controller.onKey(KeyAction.Backspace)
        assertEquals(listOf(8), engine.codes)
        assertEquals("s", composing.toString())
    }

    @Test
    fun tappingACandidateSelectsThatIndex() {
        val engine = ScriptedEngine()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, StringBuilder(), composing, states)
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        controller.onCandidate(states.last().candidates.candidates[1])
        assertEquals(listOf(1), engine.selects)
        assertEquals("妳好", composing.toString())
    }

    @Test
    fun punctuationIsNotAnEngineKey() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val controller = chiakeyController(engine, field, StringBuilder(), mutableListOf())
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        controller.onKey(KeyAction.Punctuation("。"))
        assertEquals("su3cl3", engine.asciiLog.toString())
        assertEquals(emptyList<Int>(), engine.codes)
        assertEquals("你好。", field.toString())
    }

    @Test
    fun aMissingLexiconDoesNotInsertLetters() {
        val engine = ScriptedEngine(ready = false)
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        var downloads = 0
        val controller = chiakeyController(engine, field, StringBuilder(), states) { downloads += 1 }
        controller.onStartInput(false)
        assertEquals("", states.last().candidates.message)
        type(controller, "ㄋ")
        assertEquals("", field.toString())
        assertEquals("", engine.asciiLog.toString())
        assertEquals(listOf(MISSING), states.last().candidates.candidates.map { it.text })
        controller.onCandidate(states.last().candidates.candidates.single())
        assertEquals(0, downloads)
        assertEquals(listOf(CONSENT), states.last().candidates.candidates.map { it.text })
        controller.onCandidate(states.last().candidates.candidates.single())
        assertEquals(1, downloads)
        assertEquals("", field.toString())
    }

    @Test
    fun englishStillTypesWithoutALexicon() {
        val engine = ScriptedEngine(ready = false)
        val field = StringBuilder()
        val controller = chiakeyController(engine, field, StringBuilder(), mutableListOf())
        controller.onStartInput(false)
        controller.onKey(KeyAction.Character('a'))
        assertEquals("a", field.toString())
        assertEquals("", engine.asciiLog.toString())
    }

    @Test
    fun anUntonedSentenceStaysSeveralSyllablesAndTheFirstGuessCommits() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            composing,
            states,
            partial = sampleLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄋㄧㄏㄠ")
        assertEquals("", engine.asciiLog.toString())
        assertEquals(listOf(8, 8), engine.codes)
        assertEquals("ㄋㄧㄏㄠ", composing.toString())
        assertEquals("ㄋㄧㄏㄠ", states.last().preview)
        assertEquals("你好", states.last().candidates.candidates.first().text)
        controller.onKey(KeyAction.Enter)
        assertEquals("你好", field.toString())
        assertEquals("", composing.toString())
        assertTrue(engine.codes.none { it == 13 })
    }

    @Test
    fun aToneStillGoesToTheEngineBeforeTheNextSyllable() {
        val engine = ScriptedEngine()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, StringBuilder(), StringBuilder(), states)
        controller.onStartInput(false)
        type(controller, "ㄋㄧ")
        assertEquals("ㄋㄧ", states.last().preview)
        type(controller, "ˇㄏㄠˇ")
        assertEquals("su3cl3", engine.asciiLog.toString())
        assertEquals("你好", states.last().preview)
    }

    @Test
    fun aTonedSingleOffersTheUnigramWhenTheEngineListsNothing() {
        val cases = listOf(
            "ㄇㄨˋ" to "木",
            "ㄨㄛˇ" to "我",
            "ㄓㄨˋ" to "住",
            "ㄌㄨㄢˋ" to "亂",
        )
        for ((reading, hanzi) in cases) {
            val engine = ScriptedEngine()
            val field = StringBuilder()
            val composing = StringBuilder()
            val states = mutableListOf<ImeUiState>()
            val controller = chiakeyController(
                engine,
                field,
                composing,
                states,
                partial = toneLexicon(),
            )
            controller.onStartInput(false)
            type(controller, reading)
            assertEquals("$reading must not be answered by the engine", emptyList<String>(), engine.candidatesOfLastFrame())
            val offered = states.last().candidates.candidates
            val chip = offered.find { it.text == hanzi }
            assertTrue(
                "$reading should offer $hanzi from the unigram, got ${offered.map { it.text }}",
                chip != null && chip.source == CandidateSource.SYNTHETIC && chip.engineIndex < 0,
            )
            controller.onCandidate(chip!!)
            assertEquals(emptyList<Int>(), engine.selects)
            assertEquals("", field.toString())
            assertEquals(hanzi, states.last().preview)
            assertEquals(hanzi, composing.toString())
            assertEquals("", engine.asciiLog.toString())
            controller.onKey(KeyAction.Enter)
            assertEquals(hanzi, field.toString())
            assertEquals("", composing.toString())
            assertEquals("", states.last().preview)
            assertTrue(engine.codes.none { it == 13 })
        }
    }

    @Test
    fun aFiveSyllableSentenceCommitsInTypedOrder() {
        val reading = "ㄐㄧㄣㄊㄧㄢㄏㄠㄎㄞㄒㄧㄣ"
        val full = openSentence()
        type(full.controller, reading)
        assertEquals(reading, full.states.last().preview)
        assertEquals("今天好開心", full.states.last().candidates.candidates.first().text)
        full.controller.onKey(KeyAction.Enter)
        assertEquals("今天好開心", full.field.toString())
        assertEquals("", full.composing.toString())
        assertEquals("", full.states.last().preview)
        assertTrue(full.engine.codes.none { it == 13 })

        val prefix = openSentence()
        type(prefix.controller, reading)
        val today = prefix.states.last().candidates.candidates.first { it.text == "今天" }
        assertEquals(CandidateSource.SYNTHETIC, today.source)
        prefix.controller.onCandidate(today)
        assertEquals("", prefix.field.toString())
        assertEquals("今天ㄏㄠㄎㄞㄒㄧㄣ", prefix.states.last().preview)
        assertEquals("好開心", prefix.states.last().candidates.candidates.first().text)
        prefix.controller.onKey(KeyAction.Enter)
        assertEquals("今天好開心", prefix.field.toString())
        assertEquals("", prefix.states.last().preview)

        val hello = openSentence()
        type(hello.controller, "ㄋㄧㄏㄠ")
        assertEquals("ㄋㄧㄏㄠ", hello.states.last().preview)
        hello.controller.onKey(KeyAction.Enter)
        assertEquals("你好", hello.field.toString())
        assertEquals("", hello.states.last().preview)
    }

    @Test
    fun selectingAPrefixKeepsTheTonedTailSelectable() {
        val reading = "ㄋㄧㄘㄞㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ"
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        lateinit var controller: CandidateController
        controller = chiakeyController(
            engine,
            field,
            composing,
            states,
            partial = condemnationLexicon(),
            onEditorSelection = { candidatesStart, candidatesEnd ->
                controller.onUpdateSelection(
                    oldSelStart = 0,
                    oldSelEnd = 0,
                    newSelStart = field.length,
                    newSelEnd = field.length,
                    candidatesStart = candidatesStart,
                    candidatesEnd = candidatesEnd,
                )
            },
        )
        controller.onStartInput(false)
        type(controller, reading)
        val prefix = states.last().candidates.candidates.first { it.text == "你才" }
        assertEquals(2, prefix.span)
        assertEquals(CandidateSource.SYNTHETIC, prefix.source)
        controller.onCandidate(prefix)
        assertEquals("", field.toString())
        assertEquals("你才ㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ", states.last().preview)
        val hui = states.last().candidates.candidates.first { it.text == "會" }
        assertEquals(CandidateSource.SYNTHETIC, hui.source)
        controller.onCandidate(hui)
        assertEquals("", field.toString())
        assertEquals("你才會ㄗㄠㄊㄧㄢㄑㄧㄢˇ", states.last().preview)
        val rest = states.last().candidates.candidates.first { it.text == "遭天譴" }
        controller.onCandidate(rest)
        assertEquals("", field.toString())
        assertEquals("你才會遭天譴", states.last().preview)
        controller.onKey(KeyAction.Enter)
        assertEquals("你才會遭天譴", field.toString())
        assertEquals("", states.last().preview)
        assertEquals("", composing.toString())
    }

    @Test
    fun spaceAfterAChosenPrefixDoesNotGlueItToTheTail() {
        val reading = "ㄋㄧㄘㄞㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ"
        val tail = "ㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ"
        val engine = ScriptedEngine(stickyComposing = "你才")
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        lateinit var controller: CandidateController
        controller = chiakeyController(
            engine,
            field,
            composing,
            states,
            partial = condemnationLexicon(),
            onEditorSelection = { candidatesStart, candidatesEnd ->
                controller.onUpdateSelection(
                    oldSelStart = 0,
                    oldSelEnd = 0,
                    newSelStart = field.length,
                    newSelEnd = field.length,
                    candidatesStart = candidatesStart,
                    candidatesEnd = candidatesEnd,
                )
            },
        )
        controller.onStartInput(false)
        type(controller, reading)
        val prefix = states.last().candidates.candidates.first { it.text == "你才" }
        controller.onCandidate(prefix)
        assertEquals("", field.toString())
        assertEquals("你才$tail", states.last().preview)
        controller.onKey(KeyAction.Space)
        assertEquals("", field.toString())
        assertEquals("你才$tail", states.last().preview)
        assertEquals("你才$tail", composing.toString())
        assertFalse(engine.asciiLog.toString().contains(' '))
        type(controller, "ㄉ")
        assertEquals("", field.toString())
        assertEquals("你才${tail}ㄉ", states.last().preview)
        assertFalse(states.last().preview.startsWith("你才你才"))
    }

    @Test
    fun movingTheCaretAwayDropsAHeldTail() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        lateinit var controller: CandidateController
        controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = condemnationLexicon(),
            onEditorSelection = { candidatesStart, candidatesEnd ->
                controller.onUpdateSelection(
                    oldSelStart = 0,
                    oldSelEnd = 0,
                    newSelStart = field.length,
                    newSelEnd = field.length,
                    candidatesStart = candidatesStart,
                    candidatesEnd = candidatesEnd,
                )
            },
        )
        controller.onStartInput(false)
        type(controller, "ㄋㄧㄘㄞㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ")
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "你才" })
        assertEquals("你才ㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ", states.last().preview)
        controller.onUpdateSelection(2, 2, 0, 0, -1, -1)
        assertEquals("", states.last().preview)
        assertTrue(states.last().candidates.candidates.none { it.text == "會" })
    }

    @Test
    fun choosingTianThenAhStaysInTypedOrder() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = levelToneLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄊㄧㄢ")
        controller.onKey(KeyAction.Space)
        type(controller, "ㄚ")
        val tian = states.last().candidates.candidates.first { it.text == "天" }
        controller.onCandidate(tian)
        assertEquals("", field.toString())
        assertEquals("天ㄚ", states.last().preview)
        val ah = states.last().candidates.candidates.first { it.text == "啊" }
        controller.onCandidate(ah)
        assertEquals("天啊", states.last().preview)
        assertEquals("", field.toString())
        controller.onKey(KeyAction.Enter)
        assertEquals("天啊", field.toString())
        assertEquals("", states.last().preview)
    }

    @Test
    fun choosingTianBeforeTheNextSyllableKeepsItInFront() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = levelToneLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄊㄧㄢ")
        controller.onKey(KeyAction.Space)
        val tian = states.last().candidates.candidates.first { it.text == "天" }
        controller.onCandidate(tian)
        assertEquals("天", states.last().preview)
        assertEquals("", field.toString())
        type(controller, "ㄚ")
        assertEquals("天ㄚ", states.last().preview)
    }

    @Test
    fun aChosenPrefixStaysAheadOfTheTail() {
        val reading = "ㄋㄧㄘㄞㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ"
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = condemnationLexicon(),
        )
        controller.onStartInput(false)
        type(controller, reading)
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "你才" })
        assertEquals("", field.toString())
        assertEquals("你才ㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ", states.last().preview)
        assertTrue(states.last().candidates.candidates.any { it.text == "會" })
        controller.onKey(KeyAction.Space)
        assertEquals("你才ㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ", states.last().preview)
        assertFalse(engine.asciiLog.toString().contains(' '))
    }

    @Test
    fun spaceAfterAFinishedChoiceCommitsItAndInsertsASpace() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = levelToneLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄊㄧㄢ")
        controller.onKey(KeyAction.Space)
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "天" })
        assertEquals("天", states.last().preview)
        assertEquals("", field.toString())
        controller.onKey(KeyAction.Space)
        assertEquals("天 ", field.toString())
        assertEquals("", states.last().preview)
    }

    @Test
    fun punctuationCommitsAFinishedChoice() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = levelToneLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄊㄧㄢ")
        controller.onKey(KeyAction.Space)
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "天" })
        assertEquals("天", states.last().preview)
        assertEquals("", field.toString())
        controller.onKey(KeyAction.Punctuation("。"))
        assertEquals("天。", field.toString())
        assertEquals("", states.last().preview)
    }

    @Test
    fun enterAfterLevelToneSelectsThePartialWithoutSendingKey13() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = levelToneLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄊㄧㄢ")
        controller.onKey(KeyAction.Space)
        controller.onKey(KeyAction.Enter)
        assertEquals("天", field.toString())
        assertEquals("", states.last().preview)
        assertTrue(engine.codes.none { it == 13 })
        assertFalse(engine.asciiLog.toString().contains(' '))
    }

    private fun openSentence(): Sentence {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            composing,
            states,
            partial = sentenceLexicon(),
        )
        controller.onStartInput(false)
        return Sentence(engine, field, composing, states, controller)
    }

    private class Sentence(
        val engine: ScriptedEngine,
        val field: StringBuilder,
        val composing: StringBuilder,
        val states: MutableList<ImeUiState>,
        val controller: CandidateController,
    )

    private fun type(controller: CandidateController, glyphs: String) {
        val spelling = ZhuyinSpelling.keysOf(glyphs)
        for (index in glyphs.indices) {
            controller.onKey(KeyAction.Bopomofo(glyphs[index].toString(), spelling[index]))
        }
    }
}

private fun sampleLexicon(): PartialLexicon = PartialLexicon.index(
    listOf(
        UnigramRow.of("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -0.5),
        UnigramRow.of("你", listOf("ㄋㄧˇ"), -2.0),
        UnigramRow.of("好", listOf("ㄏㄠˇ"), -2.0),
    ),
)

/** 天 is 平聲. 甜 is second tone. An unmarked syllable matches both. */
private fun levelToneLexicon(): PartialLexicon = PartialLexicon.index(
    listOf(
        UnigramRow.of("天", listOf("ㄊㄧㄢ"), -1.0),
        UnigramRow.of("甜", listOf("ㄊㄧㄢˊ"), -1.2),
        UnigramRow.of("啊", listOf("ㄚ"), -1.1),
    ),
)

/** Small 千秋 unigram fixture. The engine double does not know these rows. */
private fun toneLexicon(): PartialLexicon = PartialLexicon.index(
    listOf(
        UnigramRow.of("木", listOf("ㄇㄨˋ"), -1.0),
        UnigramRow.of("母", listOf("ㄇㄨˇ"), -1.1),
        UnigramRow.of("我", listOf("ㄨㄛˇ"), -0.8),
        UnigramRow.of("住", listOf("ㄓㄨˋ"), -1.2),
        UnigramRow.of("注", listOf("ㄓㄨˋ"), -1.3),
        UnigramRow.of("亂", listOf("ㄌㄨㄢˋ"), -1.4),
    ),
)

/** 你才會遭天譴. The last syllable is the only one with a tone, as typed. */
private fun condemnationLexicon(): PartialLexicon = PartialLexicon.index(
    listOf(
        UnigramRow.of("你才", listOf("ㄋㄧˇ", "ㄘㄞˊ"), -0.4),
        UnigramRow.of("你", listOf("ㄋㄧˇ"), -2.0),
        UnigramRow.of("才", listOf("ㄘㄞˊ"), -2.0),
        UnigramRow.of("會遭天譴", listOf("ㄏㄨㄟˋ", "ㄗㄠ", "ㄊㄧㄢ", "ㄑㄧㄢˇ"), -0.9),
        UnigramRow.of("會", listOf("ㄏㄨㄟˋ"), -1.5),
        UnigramRow.of("遭", listOf("ㄗㄠ"), -2.0),
        UnigramRow.of("天", listOf("ㄊㄧㄢ"), -2.0),
        UnigramRow.of("遭天譴", listOf("ㄗㄠ", "ㄊㄧㄢ", "ㄑㄧㄢˇ"), -1.0),
        UnigramRow.of("譴", listOf("ㄑㄧㄢˇ"), -1.6),
    ),
)

/** Five syllables need more than one indexed word. 你好 stays in the same table. */
private fun sentenceLexicon(): PartialLexicon = PartialLexicon.index(
    listOf(
        UnigramRow.of("今天", listOf("ㄐㄧㄣ", "ㄊㄧㄢ"), -1.0),
        UnigramRow.of("好開心", listOf("ㄏㄠˇ", "ㄎㄞ", "ㄒㄧㄣ"), -1.2),
        UnigramRow.of("開心", listOf("ㄎㄞ", "ㄒㄧㄣ"), -1.1),
        UnigramRow.of("好", listOf("ㄏㄠˇ"), -1.8),
        UnigramRow.of("號", listOf("ㄏㄠˋ"), -1.4),
        UnigramRow.of("開", listOf("ㄎㄞ"), -3.0),
        UnigramRow.of("心", listOf("ㄒㄧㄣ"), -3.0),
        UnigramRow.of("今", listOf("ㄐㄧㄣ"), -3.0),
        UnigramRow.of("天", listOf("ㄊㄧㄢ"), -3.0),
        UnigramRow.of("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -0.5),
        UnigramRow.of("你", listOf("ㄋㄧˇ"), -2.0),
        UnigramRow.of("好", listOf("ㄏㄠˇ"), -2.0),
    ),
)

private const val MISSING = "詞庫尚未下載"
private const val CONSENT =
    "詞庫來自千秋輸入法綜合詞庫，含 CC BY-NC 資料，僅供非商業使用，打過的字不會上傳。點此下載"

private class ScriptedEngine(
    override val ready: Boolean = true,
    private val stickyComposing: String = "",
    private val answered: Set<String> = emptySet(),
) : ZhuyinEngine {
    val asciiLog = StringBuilder()
    val codes = mutableListOf<Int>()
    val selects = mutableListOf<Int>()
    var acks = 0
    private var lastCandidates: List<String> = emptyList()

    /** What the engine itself last published. Empty unless the scripted phrase is `su3cl3`. */
    fun candidatesOfLastFrame(): List<String> = lastCandidates

    override fun handleAscii(key: Char): ChiaKeyFrame {
        asciiLog.append(key)
        val typed = asciiLog.toString()
        val frame = when {
            typed == "su3cl3" -> ChiaKeyFrame("", "你好", "", true, listOf("你好", "妳好"))
            typed in answered -> ChiaKeyFrame("", "你", "", true, listOf("你"))
            else -> ChiaKeyFrame(typed, stickyComposing, "", false, emptyList())
        }
        lastCandidates = frame.candidates
        return frame
    }

    override fun handleKey(code: Int): ChiaKeyFrame {
        codes += code
        if (code == 8 && asciiLog.isNotEmpty()) asciiLog.deleteAt(asciiLog.lastIndex)
        if (code == 13 && asciiLog.toString() == "su3cl3") {
            return ChiaKeyFrame("", "", "你好", false, emptyList())
        }
        return ChiaKeyFrame(asciiLog.toString(), stickyComposing, "", asciiLog.isNotEmpty(), emptyList())
    }

    override fun select(index: Int): ChiaKeyFrame {
        selects += index
        return ChiaKeyFrame("", "妳好", "", false, emptyList())
    }

    override fun acknowledge() {
        acks += 1
    }

    override fun reset() = Unit
}

private fun chiakeyController(
    engine: ZhuyinEngine,
    field: StringBuilder,
    composing: StringBuilder,
    states: MutableList<ImeUiState>,
    partial: PartialLexicon = PartialLexicon.EMPTY,
    onEditorSelection: ((candidatesStart: Int, candidatesEnd: Int) -> Unit)? = null,
    onRequestLexicon: () -> Unit = {},
): CandidateController {
    var batch = 0
    var reportAfterBatch = false
    fun reportSelection() {
        val hook = onEditorSelection ?: return
        if (composing.isNotEmpty()) hook(0, composing.length) else hook(-1, -1)
    }
    val connection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "beginBatchEdit" -> {
                batch += 1
                true
            }
            "endBatchEdit" -> {
                if (batch > 0) batch -= 1
                if (batch == 0 && reportAfterBatch) {
                    reportAfterBatch = false
                    reportSelection()
                }
                true
            }
            "commitText" -> {
                field.append(args?.get(0)?.toString().orEmpty())
                composing.setLength(0)
                if (batch > 0) reportAfterBatch = true else reportSelection()
                true
            }
            "setComposingText" -> {
                composing.setLength(0)
                composing.append(args?.get(0)?.toString().orEmpty())
                true
            }
            "getTextBeforeCursor" -> field.toString()
            "finishComposingText", "deleteSurroundingText" -> true
            "getSelectedText" -> null
            "toString" -> "field"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    } as InputConnection
    val lexicon = object : ZhuyinLexicon {
        override val status = EngineStatus.MISSING_LIBRARY
        override val statusDetail = ""
        override val isReady = false
        override fun start(context: android.content.Context, onReady: () -> Unit) = Unit
        override fun close() = Unit
        override fun lookup(queries: List<String>, before: String) = emptyList<Candidate>()
        override fun learn(phrases: List<Pair<String, String>>) = Unit
    }
    return CandidateController(
        context = ContextWrapper(null),
        lexicon = lexicon,
        pipeline = CandidatePipeline(),
        connection = { connection },
        editorInfo = { EditorInfo() },
        onUi = { states.add(it) },
        post = { it.run() },
        zhuyinEngine = engine,
        chiakey = true,
        onRequestLexicon = onRequestLexicon,
        partialLexicon = partial,
    )
}
