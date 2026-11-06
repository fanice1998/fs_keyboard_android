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
    fun spaceIsSentToTheEngine() {
        val engine = ScriptedEngine()
        val controller = chiakeyController(engine, StringBuilder(), StringBuilder(), mutableListOf())
        controller.onStartInput(false)
        type(controller, "ㄋㄧˇ")
        controller.onKey(KeyAction.Space)
        assertEquals("su3 ", engine.asciiLog.toString())
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
        assertEquals(listOf("ㄋ", "ㄧ", "ㄏ", "ㄠ"), states.last().preview)
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
        assertEquals(listOf("ㄋ", "ㄧ"), states.last().preview)
        type(controller, "ˇㄏㄠˇ")
        assertEquals("su3cl3", engine.asciiLog.toString())
        assertEquals(listOf("你", "好"), states.last().preview)
    }

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

private const val MISSING = "詞庫尚未下載"
private const val CONSENT =
    "詞庫來自千秋輸入法綜合詞庫，含 CC BY-NC 資料，僅供非商業使用，打過的字不會上傳。點此下載"

private class ScriptedEngine(override val ready: Boolean = true) : ZhuyinEngine {
    val asciiLog = StringBuilder()
    val codes = mutableListOf<Int>()
    val selects = mutableListOf<Int>()
    var acks = 0

    override fun handleAscii(key: Char): ChiaKeyFrame {
        asciiLog.append(key)
        val typed = asciiLog.toString()
        return when (typed) {
            "su3cl3" -> ChiaKeyFrame("", "你好", "", true, listOf("你好", "妳好"))
            else -> ChiaKeyFrame(typed, "", "", false, emptyList())
        }
    }

    override fun handleKey(code: Int): ChiaKeyFrame {
        codes += code
        if (code == 8 && asciiLog.isNotEmpty()) asciiLog.deleteAt(asciiLog.lastIndex)
        if (code == 13 && asciiLog.toString() == "su3cl3") {
            return ChiaKeyFrame("", "", "你好", false, emptyList())
        }
        return ChiaKeyFrame(asciiLog.toString(), "", "", asciiLog.isNotEmpty(), emptyList())
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
    onRequestLexicon: () -> Unit = {},
): CandidateController {
    val connection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "commitText" -> {
                field.append(args?.get(0)?.toString().orEmpty())
                composing.setLength(0)
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
