package com.fskeyboard.ime.candidate

import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * English suggestions for a letter typed into an already committed prefix.
 * The handler is not [EnglishCompositionControllerTest.Field]: that one ignores the caret.
 */
class EnglishPrefixComposeTest {

    @Test
    fun aLetterAfterACommittedPrefixLooksUpHellNotL() {
        val field = CaretField().apply { seed("hel") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.Character('l'))

        assertEquals("l", field.underlined())
        assertEquals("hell", field.text.toString())
        val shown = row(states)
        assertTrue("row=$shown", shown.any { it.text == "hello" })
        assertTrue("row=$shown", shown.any { it.text == "hell" })
        assertFalse(shown.all { it.text == "l" })
    }

    @Test
    fun atMostEightCandidates() {
        val lines = ArrayList<String>()
        lines.add("hello\t100")
        for (i in 0 until 12) {
            lines.add("hell${'a' + i}\t${90 - i}")
        }
        val field = CaretField().apply { seed("hel") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, *lines.toTypedArray())
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.Character('l'))

        val shown = row(states)
        assertTrue(shown.size <= 8)
        assertTrue("row=$shown", shown.any { it.text == "hello" })
    }

    @Test
    fun caseComesOnlyFromApplyCase() {
        val field = CaretField().apply { seed("He") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.Character('l'))

        val shown = row(states)
        assertEquals("Hello", shown.first().text)
        assertTrue("row=$shown", shown.any { it.text == "Hel" })
        assertFalse(shown.any { it.text == "hello" })
        assertFalse(shown.any { it.text == "l" })
    }

    @Test
    fun tappingACandidateReplacesTheWholePrefix() {
        val field = CaretField().apply { seed("hel") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.Character('l'))

        val hello = row(states).first { it.text == "hello" }
        controller.onCandidate(hello)

        assertEquals("hello ", field.text.toString())
        assertEquals("", field.underlined())
    }

    @Test
    fun spaceCommitsTheTypedPrefixNotThePrediction() {
        val field = CaretField().apply { seed("hel") }
        val controller = controller(field)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.Character('l'))
        controller.onKey(KeyAction.Space)

        assertEquals("hell ", field.text.toString())
        assertEquals("", field.underlined())
        assertTrue(field.commits.contains("hell "))
        assertFalse(field.commits.contains("hello "))
        assertFalse(field.commits.contains("l "))
    }

    @Test
    fun enterCommitsTheFirstPredictionOverTheWholePrefix() {
        val field = CaretField().apply { seed("hel") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.Character('l'))
        controller.onKey(KeyAction.Enter)

        assertEquals("hello", field.text.toString())
        assertEquals("", field.underlined())
        assertFalse(field.text.toString().contains("helh"))
        assertTrue(field.commits.contains("hello"))
    }

    @Test
    fun movingTheCaretOntoCommittedEnglishDoesNotPredict() {
        val field = CaretField().apply { seed("hello") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        val end = field.text.length
        controller.onUpdateSelection(end, end, end, end, -1, -1)

        assertFalse(row(states).any { it.text == "hello" })
    }

    @Test
    fun aSpaceGlideDoesNotQueryEnglish() {
        val field = CaretField().apply { seed("hello") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))

        assertTrue(controller.beginCursor())
        controller.endCursor()

        assertFalse(row(states).any { it.text == "hello" })
    }

    @Test
    fun refreshIdleSuggestionsDoesNotQueryEnglish() {
        val field = CaretField().apply { seed("hello") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        val method = CandidateController::class.java.getDeclaredMethod("refreshIdleSuggestions")
        method.isAccessible = true
        method.invoke(controller)

        assertFalse(row(states).any { it.text == "hello" })
    }

    @Test
    fun aDigitDoesNotLookUpAnEnglishPrefix() {
        val field = CaretField().apply { seed("hel") }
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.Character('1'))

        assertEquals("hel1", field.text.toString())
        assertEquals("", field.underlined())
        assertFalse(row(states).any { it.text == "hello" })
    }

    @Test
    fun aSymbolHyphenStaysInsideWhenThePrefixIsTheBuffer() {
        val field = CaretField()
        val controller = controller(field)
        loadLexicon(controller, "hello\t10")
        typeLatin(controller, "hello")
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        controller.onKey(KeyAction.Character('-'))

        assertTrue(field.commits.isEmpty())
        assertEquals("hello-", field.underlined())
    }

    @Test
    fun aSymbolPeriodFinishesWhenThePrefixIsTheBuffer() {
        val field = CaretField()
        val controller = controller(field)
        loadLexicon(controller, "hello\t10")
        typeLatin(controller, "hello")
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        controller.onKey(KeyAction.Character('.'))

        assertEquals(listOf("hello."), field.commits)
        assertEquals("", field.underlined())
    }

    @Test
    fun revertingASpaceRestoresTheBufferedWord() {
        val field = CaretField()
        val controller = controller(field)
        loadLexicon(controller, "hello\t10")
        typeLatin(controller, "hello")
        controller.onKey(KeyAction.Space)

        assertEquals(listOf("hello "), field.commits)
        assertEquals("", field.underlined())

        controller.revertLastSpaceTap()

        assertEquals(1, field.deleteCalls)
        assertEquals("hello", field.underlined())
    }

    @Test
    fun atStillShowsEmailDomains() {
        val field = CaretField()
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        loadLexicon(controller, "hello\t10")
        typeLatin(controller, "user")
        controller.onKey(KeyAction.Character('@'))

        assertEquals(listOf("user@"), field.commits)
        assertEquals("", field.underlined())
        assertEquals("gmail.com", row(states).first().text)
    }

    private fun row(states: List<ImeUiState>): List<Candidate> {
        return states.lastOrNull()?.candidates?.candidates.orEmpty()
    }

    private fun typeLatin(controller: CandidateController, word: String) {
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        for (ch in word) controller.onKey(KeyAction.Character(ch))
    }

    private fun loadLexicon(controller: CandidateController, vararg lines: String) {
        val field = CandidateController::class.java.getDeclaredField("englishLexicon")
        field.isAccessible = true
        val lexicon = field.get(controller) as EnglishLexicon
        lexicon.load(lines.toList())
    }

    private class ReadyEngine : ZhuyinEngine {
        override val ready = true
        override fun handleAscii(key: Char) = ChiaKeyFrame.parse("")
        override fun handleKey(code: Int) = ChiaKeyFrame.parse("")
        override fun select(index: Int) = ChiaKeyFrame.parse("")
        override fun acknowledge() = Unit
        override fun reset() = Unit
    }

    private fun controller(
        field: CaretField,
        states: MutableList<ImeUiState> = mutableListOf(),
    ): CandidateController {
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            field,
        ) as InputConnection
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        return CandidateController(
            context = object : ContextWrapper(null) {},
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = { info },
            onUi = { states.add(it) },
            post = { it.run() },
            zhuyinEngine = ReadyEngine(),
        )
    }

    /**
     * Caret-aware field. commitText replaces only the composing span.
     * deleteSurroundingText deletes committed characters outside that span.
     * getTextBeforeCursor is only the text to the left of the caret.
     */
    private class CaretField : InvocationHandler {
        val text = StringBuilder()
        val commits = ArrayList<String>()
        var caret = 0
        var deleteCalls = 0
        var textBeforeCalls = 0
        private var composingStart = -1
        private var composingEnd = -1

        fun seed(value: String) {
            text.append(value)
            caret = text.length
            composingStart = -1
            composingEnd = -1
        }

        fun underlined(): String {
            if (composingStart < 0 || composingEnd < composingStart) return ""
            return text.substring(composingStart, composingEnd.coerceAtMost(text.length))
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "setComposingText" -> {
                    val value = args?.get(0)?.toString().orEmpty()
                    replaceComposing(value)
                    true
                }
                "commitText" -> {
                    val value = args?.get(0)?.toString().orEmpty()
                    commits += value
                    replaceComposing(value)
                    composingStart = -1
                    composingEnd = -1
                    true
                }
                "finishComposingText" -> {
                    composingStart = -1
                    composingEnd = -1
                    true
                }
                "deleteSurroundingText" -> {
                    deleteCalls += 1
                    val before = (args?.get(0) as? Number)?.toInt() ?: 0
                    val after = (args?.get(1) as? Number)?.toInt() ?: 0
                    deleteOutsideComposing(before, after)
                    true
                }
                "getTextBeforeCursor" -> {
                    textBeforeCalls += 1
                    val limit = (args?.get(0) as? Number)?.toInt() ?: Int.MAX_VALUE
                    val end = caret.coerceIn(0, text.length)
                    val all = text.substring(0, end)
                    if (limit >= all.length) all else all.substring(all.length - limit)
                }
                "getTextAfterCursor" -> {
                    val limit = (args?.get(0) as? Number)?.toInt() ?: Int.MAX_VALUE
                    val start = caret.coerceIn(0, text.length)
                    val all = text.substring(start)
                    if (limit >= all.length) all else all.substring(0, limit)
                }
                "getExtractedText" -> {
                    ExtractedText().apply {
                        this.text = this@CaretField.text.toString()
                        startOffset = 0
                        selectionStart = caret
                        selectionEnd = caret
                    }
                }
                "getSelectedText" -> null
                "setSelection" -> {
                    val start = (args?.get(0) as? Number)?.toInt() ?: caret
                    caret = start.coerceIn(0, text.length)
                    true
                }
                "beginBatchEdit", "endBatchEdit", "sendKeyEvent" -> true
                "getCursorCapsMode" -> 0
                "toString" -> "caret-field"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        }

        private fun replaceComposing(value: String) {
            if (composingStart >= 0) {
                val end = composingEnd.coerceIn(composingStart, text.length)
                text.replace(composingStart, end, value)
                caret = composingStart + value.length
                if (value.isEmpty()) {
                    composingStart = -1
                    composingEnd = -1
                } else {
                    composingEnd = caret
                }
            } else {
                val at = caret.coerceIn(0, text.length)
                text.insert(at, value)
                caret = at + value.length
                if (value.isEmpty()) {
                    composingStart = -1
                    composingEnd = -1
                } else {
                    composingStart = at
                    composingEnd = caret
                }
            }
        }

        private fun deleteOutsideComposing(before: Int, after: Int) {
            if (composingStart < 0) {
                val from = (caret - before).coerceAtLeast(0)
                val to = (caret + after).coerceAtMost(text.length)
                text.delete(from, to)
                caret = from
                return
            }
            val outsideBefore = if (caret <= composingStart) caret else composingStart
            val drop = before.coerceAtMost(outsideBefore)
            if (drop > 0) {
                text.delete(outsideBefore - drop, outsideBefore)
                if (caret >= outsideBefore) caret -= drop
                composingStart -= drop
                composingEnd -= drop
            }
            if (after > 0) {
                val from = if (caret < composingEnd && caret >= composingStart) composingEnd else caret
                val to = (from + after).coerceAtMost(text.length)
                val removed = to - from
                if (removed > 0) text.delete(from, to)
            }
        }
    }
}
