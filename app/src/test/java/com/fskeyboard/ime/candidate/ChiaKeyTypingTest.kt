package com.fskeyboard.ime.candidate

import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.EngineStatus
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.ZhuyinSpelling
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ChiaKeyTypingTest {
    @Test
    fun aTonedReadingStaysBopomofoUntilEnterWritesIt() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, field, composing, states)
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        assertEquals("", engine.asciiLog.toString())
        assertEquals("ㄋㄧˇㄏㄠˇ", composing.toString())
        assertEquals("ㄋㄧˇㄏㄠˇ", states.last().preview)
        controller.onKey(KeyAction.Enter)
        assertEquals(emptyList<Int>(), engine.codes)
        assertEquals("ㄋㄧˇㄏㄠˇ", field.toString())
        assertEquals("", composing.toString())
        assertEquals("", states.last().preview)
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
        assertEquals("", engine.asciiLog.toString())
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
        assertEquals("", log)
        // 平聲 has no glyph, and the third tone does. Each clears before its symbol.
        var guards = 0
        while (states.last().preview.isNotEmpty() && guards < reading.length + 2) {
            controller.onKey(KeyAction.Backspace)
            guards += 1
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
    fun backspaceDeletesABopomofoSymbolFromThePreview() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, field, composing, states)
        controller.onStartInput(false)
        type(controller, "ㄋㄧ")
        controller.onKey(KeyAction.Backspace)
        assertEquals(emptyList<Int>(), engine.codes)
        assertEquals("ㄋ", states.last().preview)
        assertEquals("ㄋ", composing.toString())
        assertEquals("", field.toString())
    }

    @Test
    fun tappingNiHaoWritesItWithoutAskingTheEngine() {
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
        type(controller, "ㄋㄧˇㄏㄠˇ")
        assertEquals("ㄋㄧˇㄏㄠˇ", states.last().preview)
        val chip = states.last().candidates.candidates.first { it.text == "你好" }
        controller.onCandidate(chip)
        assertEquals(emptyList<Int>(), engine.selects)
        assertEquals("你好", field.toString())
        assertEquals("", composing.toString())
        assertEquals("", states.last().preview)
    }

    @Test
    fun anUnpickedReadingStaysBopomofoInsteadOfAnEngineGuess() {
        val glyphs = "ㄋㄧˇㄍ"
        val engine = ScriptedEngine(
            answered = setOf(ZhuyinSpelling.keysOf("ㄋㄧˇ")),
            openOffer = OpenOffer(
                ascii = ZhuyinSpelling.keysOf(glyphs),
                reading = "ㄍ",
                composing = "你",
                candidates = listOf("功能"),
                selected = "功能",
            ),
        )
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(engine, StringBuilder(), composing, states)
        controller.onStartInput(false)
        type(controller, glyphs)
        assertEquals("ㄋㄧˇㄍ", states.last().preview)
        assertEquals("ㄋㄧˇㄍ", composing.toString())
        assertEquals(emptyList<Int>(), engine.selects)
        assertFalse(states.last().preview.contains("你"))
    }

    @Test
    fun punctuationIsNotAnEngineKey() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val controller = chiakeyController(engine, field, StringBuilder(), mutableListOf())
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        controller.onKey(KeyAction.Punctuation("。"))
        assertEquals("", engine.asciiLog.toString())
        assertEquals(emptyList<Int>(), engine.codes)
        assertEquals("ㄋㄧˇㄏㄠˇ。", field.toString())
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
        assertEquals(emptyList<Int>(), engine.codes)
        assertEquals("ㄋㄧㄏㄠ", composing.toString())
        assertEquals("ㄋㄧㄏㄠ", states.last().preview)
        assertEquals("你好", states.last().candidates.candidates.first().text)
        controller.onKey(KeyAction.Enter)
        assertEquals("ㄋㄧㄏㄠ", field.toString())
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
        assertEquals("", engine.asciiLog.toString())
        assertEquals("ㄋㄧˇㄏㄠˇ", states.last().preview)
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
            assertEquals(hanzi, field.toString())
            assertEquals("", states.last().preview)
            assertEquals("", composing.toString())
            assertTrue(states.last().candidates.candidates.isEmpty())
            assertFalse(states.last().hasUnconfirmedText)
            assertEquals("", engine.asciiLog.toString())
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
        assertEquals(reading, full.field.toString())
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
        assertEquals("今天ㄏㄠㄎㄞㄒㄧㄣ", prefix.field.toString())
        assertEquals("", prefix.states.last().preview)

        val hello = openSentence()
        type(hello.controller, "ㄋㄧㄏㄠ")
        assertEquals("ㄋㄧㄏㄠ", hello.states.last().preview)
        hello.controller.onKey(KeyAction.Enter)
        assertEquals("ㄋㄧㄏㄠ", hello.field.toString())
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
        assertEquals("你才會遭天譴", field.toString())
        assertEquals("", states.last().preview)
        assertEquals("", composing.toString())
        assertTrue(states.last().candidates.candidates.isEmpty())
        assertTrue(engine.codes.none { it == 13 })
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
        assertEquals("天啊", field.toString())
        assertEquals("", states.last().preview)
        assertTrue(states.last().candidates.candidates.isEmpty())
        assertTrue(engine.codes.none { it == 13 })
    }

    @Test
    fun choosingTianWritesItBeforeTheNextSyllableStarts() {
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
        assertEquals("天", field.toString())
        assertEquals("", states.last().preview)
        assertTrue(states.last().candidates.candidates.isEmpty())
        type(controller, "ㄚ")
        assertEquals("天", field.toString())
        assertEquals("ㄚ", states.last().preview)
    }

    @Test
    fun aFinishedChoiceWritesBeforeTheNextTonedSyllable() {
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
        assertEquals("天", field.toString())
        assertEquals("", states.last().preview)
        type(controller, "ㄚˇ")
        assertEquals("天", field.toString())
        assertEquals("ㄚˇ", states.last().preview)
        controller.onKey(KeyAction.Enter)
        assertEquals("天ㄚˇ", field.toString())
        assertEquals("", states.last().preview)
        assertTrue(engine.codes.none { it == 13 })
    }

    @Test
    fun aTonedPrefixStaysBopomofoAheadOfTheNextSyllable() {
        val engine = ScriptedEngine(answered = setOf("su3"))
        val field = StringBuilder()
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            composing,
            states,
            partial = levelToneLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇㄊㄧㄢ")
        assertEquals("ㄋㄧˇㄊㄧㄢ", states.last().preview)
        assertEquals("ㄋㄧˇㄊㄧㄢ", composing.toString())
        assertEquals("", field.toString())
        assertEquals("", engine.asciiLog.toString())
        assertEquals(emptyList<Int>(), engine.codes)
        assertTrue(states.last().candidates.candidates.none { it.engineIndex >= 0 })
        assertFalse(states.last().preview.contains("你"))
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
        assertEquals("天", field.toString())
        assertEquals("", states.last().preview)
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
        assertEquals("天", field.toString())
        assertEquals("", states.last().preview)
        controller.onKey(KeyAction.Punctuation("。"))
        assertEquals("天。", field.toString())
        assertEquals("", states.last().preview)
    }

    @Test
    fun punctuationKeepsTheReadingStillOnScreen() {
        val parked = ScriptedEngine()
        val parkedField = StringBuilder()
        val parkedStates = mutableListOf<ImeUiState>()
        val parkedController = chiakeyController(
            parked,
            parkedField,
            StringBuilder(),
            parkedStates,
            partial = toneLexicon(),
        )
        parkedController.onStartInput(false)
        type(parkedController, "ㄇㄨˋ")
        assertEquals("ㄇㄨˋ", parkedStates.last().preview)
        parkedController.onKey(KeyAction.Punctuation("。"))
        val parkedText = parkedField.toString()
        assertTrue(parkedText, parkedText == "ㄇㄨˋ。" || parkedText == "木。")
        assertEquals(emptyList<Int>(), parked.codes)

        val tail = ScriptedEngine()
        val tailField = StringBuilder()
        val tailStates = mutableListOf<ImeUiState>()
        val tailController = chiakeyController(
            tail,
            tailField,
            StringBuilder(),
            tailStates,
            partial = levelToneLexicon(),
        )
        tailController.onStartInput(false)
        type(tailController, "ㄊㄧㄢ")
        tailController.onKey(KeyAction.Space)
        tailController.onCandidate(tailStates.last().candidates.candidates.first { it.text == "天" })
        assertEquals("天", tailField.toString())
        type(tailController, "ㄚˇ")
        assertEquals("ㄚˇ", tailStates.last().preview)
        tailController.onKey(KeyAction.Punctuation("。"))
        assertEquals("天ㄚˇ。", tailField.toString())
        assertEquals("", tailStates.last().preview)
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
        assertEquals("ㄊㄧㄢ", field.toString())
        assertEquals("", states.last().preview)
        assertFalse(field.toString().contains("天"))
        assertTrue(engine.codes.none { it == 13 })
        assertFalse(engine.asciiLog.toString().contains(' '))
    }

    @Test
    fun enterOnAnUnansweredToneCommitsThePartialWithoutSendingKey13() {
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
        type(controller, "ㄇㄨˋ")
        assertEquals("ㄇㄨˋ", states.last().preview)
        assertEquals("木", states.last().candidates.candidates.first().text)
        controller.onKey(KeyAction.Enter)
        assertEquals("ㄇㄨˋ", field.toString())
        assertEquals("", composing.toString())
        assertEquals("", states.last().preview)
        assertTrue(engine.codes.none { it == 13 })
    }

    @Test
    fun anUnpickedReadingStaysBopomofoWithHanziOnlyInTheRow() {
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
        assertEquals("", field.toString())
        val texts = states.last().candidates.candidates.map { it.text }
        assertTrue(texts.contains("天"))
        assertFalse(states.last().preview.contains("天"))
    }

    @Test
    fun aPartialPickPredictsTheTailSentenceThenWordThenCharacter() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = tierLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄋㄧㄏㄠㄇㄚㄚ")
        assertEquals("ㄋㄧㄏㄠㄇㄚㄚ", states.last().preview)
        assertEquals("", field.toString())
        val open = states.last().candidates.candidates.map { it.text }
        assertEquals("你好嗎啊", open.first())
        assertTrue(open.indexOf("你好嗎") < open.indexOf("你好"))
        assertTrue(open.indexOf("你好") < open.indexOf("你"))
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "你" })
        assertEquals("", field.toString())
        assertEquals("你ㄏㄠㄇㄚㄚ", states.last().preview)
        val tail = states.last().candidates.candidates.map { it.text }
        assertEquals("好嗎啊", tail.first())
        assertTrue(tail.indexOf("好嗎啊") < tail.indexOf("好嗎"))
        assertTrue(tail.indexOf("好嗎") < tail.indexOf("好"))
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "好嗎啊" })
        assertEquals("你好嗎啊", field.toString())
        assertEquals("", states.last().preview)
        assertTrue(states.last().candidates.candidates.isEmpty())
    }

    @Test
    fun enterOnHanziAndBopomofoWritesThePreview() {
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
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "天" })
        assertEquals("", field.toString())
        assertEquals("天ㄚ", states.last().preview)
        controller.onKey(KeyAction.Enter)
        assertEquals("天ㄚ", field.toString())
        assertEquals("", states.last().preview)
        assertFalse(field.toString().contains("啊"))
    }

    @Test
    fun backspaceRestoresTheLatestHanziToItsBopomofo() {
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
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "天" })
        assertEquals("天ㄚ", states.last().preview)
        controller.onKey(KeyAction.Backspace)
        assertEquals("", field.toString())
        assertEquals("ㄊㄧㄢㄚ", states.last().preview)
        val texts = states.last().candidates.candidates.map { it.text }
        assertTrue(texts.contains("天"))
        assertFalse(texts.contains("甜"))
    }

    @Test
    fun anAssembledPhraseCanBeForgottenAndABaseWordCannot() {
        val engine = ScriptedEngine()
        val field = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            engine,
            field,
            StringBuilder(),
            states,
            partial = pieceLexicon(),
        )
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        val whole = states.last().candidates.candidates.first { it.text == "你好" }
        controller.onCandidate(whole)
        assertEquals("你好", field.toString())
        field.setLength(0)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        val pickedWhole = states.last().candidates.candidates.first { it.text == "你好" }
        assertFalse(controller.canForgetCandidate(pickedWhole))
        controller.adjustCandidate(pickedWhole, CandidateWeight.HIDE)
        assertTrue(states.last().candidates.candidates.any { it.text == "你好" })
        controller.onKey(KeyAction.Backspace)
        repeat(8) {
            if (states.last().preview.isEmpty()) return@repeat
            controller.onKey(KeyAction.Backspace)
        }

        type(controller, "ㄋㄧˇㄏㄠˇ")
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "你" })
        assertEquals("", field.toString())
        assertEquals("你ㄏㄠˇ", states.last().preview)
        controller.onCandidate(states.last().candidates.candidates.first { it.text == "好" })
        assertEquals("你好", field.toString())
        assertEquals("", states.last().preview)
        field.setLength(0)
        type(controller, "ㄋㄧˇㄏㄠˇ")
        val made = states.last().candidates.candidates.first { it.text == "你好" }
        assertTrue(controller.canForgetCandidate(made))
        controller.adjustCandidate(made, CandidateWeight.HIDE)
        val after = states.last().candidates.candidates.map { it.text }
        assertFalse(after.contains("你好"))
        assertTrue(after.contains("你"))

        val baseEngine = ScriptedEngine()
        val baseStates = mutableListOf<ImeUiState>()
        val base = chiakeyController(
            baseEngine,
            StringBuilder(),
            StringBuilder(),
            baseStates,
            partial = levelToneLexicon(),
        )
        base.onStartInput(false)
        type(base, "ㄊㄧㄢ")
        val tian = baseStates.last().candidates.candidates.first { it.text == "天" }
        assertFalse(base.canForgetCandidate(tian))
        base.adjustCandidate(tian, CandidateWeight.PROMOTE)
        assertFalse(base.canForgetCandidate(baseStates.last().candidates.candidates.first { it.text == "天" }))
        base.adjustCandidate(baseStates.last().candidates.candidates.first { it.text == "天" }, CandidateWeight.HIDE)
        assertTrue(baseStates.last().candidates.candidates.any { it.text == "天" })
    }

    @Test
    fun hidingAnEnglishWordRemovesItFromLaterSuggestions() {
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val states = mutableListOf<ImeUiState>()
        val controller = chiakeyController(
            ScriptedEngine(),
            StringBuilder(),
            StringBuilder(),
            states,
            editorInfo = { info },
            composingCountsAsBefore = true,
        )
        controller.onStartInput(false)
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        for (ch in "qxword") controller.onKey(KeyAction.Character(ch))
        controller.onKey(KeyAction.Space)
        controller.onKey(KeyAction.Character('q'))
        val learned = states.last().candidates.candidates.first { it.text == "qxword" }
        assertTrue(controller.canForgetCandidate(learned))
        controller.adjustCandidate(learned, CandidateWeight.HIDE)
        assertFalse(states.last().candidates.candidates.any { it.text == "qxword" })
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

/** 你 and 好 are separate rows. 你好 appears only when those pieces are put together. */
private fun pieceLexicon(): PartialLexicon = PartialLexicon.index(
    listOf(
        UnigramRow.of("你", listOf("ㄋㄧˇ"), -1.0),
        UnigramRow.of("好", listOf("ㄏㄠˇ"), -1.0),
    ),
)

/** A four-syllable sentence, a shorter word, and one character, so the row can be ordered. */
private fun tierLexicon(): PartialLexicon = PartialLexicon.index(
    listOf(
        UnigramRow.of("你好嗎啊", listOf("ㄋㄧˇ", "ㄏㄠˇ", "ㄇㄚ", "ㄚ"), -0.2),
        UnigramRow.of("你好嗎", listOf("ㄋㄧˇ", "ㄏㄠˇ", "ㄇㄚ"), -0.3),
        UnigramRow.of("好嗎啊", listOf("ㄏㄠˇ", "ㄇㄚ", "ㄚ"), -0.3),
        UnigramRow.of("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -0.4),
        UnigramRow.of("好嗎", listOf("ㄏㄠˇ", "ㄇㄚ"), -0.5),
        UnigramRow.of("你", listOf("ㄋㄧˇ"), -1.0),
        UnigramRow.of("好", listOf("ㄏㄠˇ"), -1.1),
        UnigramRow.of("嗎", listOf("ㄇㄚ"), -1.2),
        UnigramRow.of("啊", listOf("ㄚ"), -1.3),
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

private data class OpenOffer(
    val ascii: String,
    val reading: String,
    val composing: String,
    val candidates: List<String>,
    val selected: String,
)

private class ScriptedEngine(
    override val ready: Boolean = true,
    private val stickyComposing: String = "",
    private val answered: Set<String> = emptySet(),
    private val openOffer: OpenOffer? = null,
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
        val offer = openOffer
        val frame = when {
            offer != null && typed == offer.ascii ->
                ChiaKeyFrame(offer.reading, offer.composing, "", true, offer.candidates)
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
        val offer = openOffer
        if (offer != null && asciiLog.toString() == offer.ascii) {
            return ChiaKeyFrame("", offer.selected, "", false, emptyList())
        }
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
    editorInfo: () -> EditorInfo? = { EditorInfo() },
    composingCountsAsBefore: Boolean = false,
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
            "getTextBeforeCursor" -> {
                val limit = (args?.get(0) as? Number)?.toInt() ?: Int.MAX_VALUE
                val text = if (composingCountsAsBefore && composing.isNotEmpty()) {
                    composing.toString()
                } else {
                    field.toString()
                }
                if (limit >= text.length) text else text.substring(text.length - limit)
            }
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
        editorInfo = editorInfo,
        onUi = { states.add(it) },
        post = { it.run() },
        zhuyinEngine = engine,
        chiakey = true,
        onRequestLexicon = onRequestLexicon,
        partialLexicon = partial,
    )
}
