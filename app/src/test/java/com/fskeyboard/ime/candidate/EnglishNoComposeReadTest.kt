package com.fskeyboard.ime.candidate

import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
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
 * Fields where [EnglishInputPolicy.shouldCompose] is false commit a Latin letter
 * directly: no composing span, no getTextBeforeCursor read for that key, and no
 * English dictionary suggestions in the candidate row.
 */
class EnglishNoComposeReadTest {

    @Test
    fun textPasswordCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    }

    @Test
    fun visiblePasswordCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    }

    @Test
    fun webPasswordCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }

    @Test
    fun numberPasswordCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    @Test
    fun numberClassCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_NUMBER)
    }

    @Test
    fun phoneClassCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_PHONE)
    }

    @Test
    fun datetimeClassCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_DATETIME)
    }

    @Test
    fun nullTypeCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_NULL)
    }

    @Test
    fun uriVariationCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    }

    @Test
    fun filterVariationCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER)
    }

    @Test
    fun noSuggestionsFlagCommitsTheLetterWithoutReading() {
        assertLatinLetterDoesNotRead(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }

    private fun assertLatinLetterDoesNotRead(inputType: Int) {
        val field = Field()
        val states = mutableListOf<ImeUiState>()
        val info = EditorInfo().apply { this.inputType = inputType }
        val controller = controller(field, states, info)

        assertFalse(EnglishInputPolicy.shouldCompose(info))

        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        val readsBeforeLetter = field.textBeforeCalls

        controller.onKey(KeyAction.Character('a'))

        assertFalse(EnglishInputPolicy.shouldCompose(info))
        assertEquals(listOf("a"), field.committed)
        assertEquals("", field.composing)
        assertEquals(readsBeforeLetter, field.textBeforeCalls)

        val row = states.lastOrNull()?.candidates?.candidates.orEmpty()
        val englishDictionarySuggestions = row
            .filter { it.source != CandidateSource.CLIPBOARD }
            .filter { candidate ->
                candidate.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } &&
                    candidate.text.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            }
        assertTrue(
            "english dictionary suggestions in the row: $englishDictionarySuggestions",
            englishDictionarySuggestions.isEmpty(),
        )
    }

    private class Field : InvocationHandler {
        val committed = ArrayList<String>()
        val text = StringBuilder()
        var composing = ""
        var deleteCalls = 0
        var deletedBefore = 0
        var textBeforeCalls = 0

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "commitText" -> {
                    committed += args?.get(0)?.toString().orEmpty()
                    text.append(args?.get(0)?.toString().orEmpty())
                    composing = ""
                    true
                }
                "setComposingText" -> {
                    composing = args?.get(0)?.toString().orEmpty()
                    true
                }
                "getTextBeforeCursor" -> {
                    textBeforeCalls += 1
                    val limit = (args?.get(0) as? Number)?.toInt() ?: Int.MAX_VALUE
                    val all = text.toString() + composing
                    if (limit >= all.length) all else all.substring(all.length - limit)
                }
                "deleteSurroundingText" -> {
                    deleteCalls += 1
                    val n = (args?.get(0) as? Number)?.toInt() ?: 0
                    deletedBefore = n
                    val drop = n.coerceAtMost(text.length)
                    if (drop > 0) text.delete(text.length - drop, text.length)
                    true
                }
                "finishComposingText", "setSelection", "beginBatchEdit", "endBatchEdit", "sendKeyEvent" -> true
                "getSelectedText" -> null
                "toString" -> "field"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        }
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
        field: Field,
        states: MutableList<ImeUiState>,
        info: EditorInfo,
    ): CandidateController {
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            field,
        ) as InputConnection
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
}
