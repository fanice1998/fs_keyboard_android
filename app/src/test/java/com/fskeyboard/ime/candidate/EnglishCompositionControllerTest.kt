package com.fskeyboard.ime.candidate

import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.ComputerKey
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

class EnglishCompositionControllerTest {
    @Test
    fun latinAndSymbolPagesKeepTheOpenWord() {
        val field = Field()
        val controller = controller(field)
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        type(controller, "hello")

        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        assertEquals("hello", field.composing)
        assertTrue(field.committed.isEmpty())

        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        assertEquals("hello", field.composing)
        assertTrue(field.committed.isEmpty())

        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS_MORE))
        assertEquals("hello", field.composing)
        assertTrue(field.committed.isEmpty())

        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        assertEquals("hello", field.composing)
        assertTrue(field.committed.isEmpty())
    }

    @Test
    fun switchingSymbolPagesCommitsTheWord() {
        val field = Field()
        val controller = controller(field)
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        type(controller, "hello")
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        assertEquals("hello", field.composing)

        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS_MORE))

        assertEquals(listOf("hello"), field.committed)
        assertEquals("", field.composing)
    }

    @Test
    fun aSymbolPagePeriodFinishesTheWordOnce() {
        val field = Field()
        val controller = controller(field)
        openLatinWord(controller, "hello")
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))

        controller.onKey(KeyAction.Character('.'))

        assertEquals(listOf("hello."), field.committed)
        assertEquals("", field.composing)
    }

    @Test
    fun aSymbolPageHyphenStaysInsideTheWord() {
        val field = Field()
        val controller = controller(field)
        openLatinWord(controller, "hello")
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))

        controller.onKey(KeyAction.Character('-'))

        assertTrue(field.committed.isEmpty())
        assertEquals("hello-", field.composing)
    }

    @Test
    fun aLatinBaseSymbolPageCanStartAWord() {
        val field = Field()
        val controller = controller(field)
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))

        controller.onKey(KeyAction.Character('a'))

        assertTrue(field.committed.isEmpty())
        assertEquals("a", field.composing)
    }

    @Test
    fun typingAtAfterAWordShowsEmailDomains() {
        val field = Field()
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        type(controller, "user")

        controller.onKey(KeyAction.Character('@'))

        assertEquals(listOf("user@"), field.committed)
        assertEquals("", field.composing)
        assertEquals("gmail.com", states.last().candidates.candidates.first().text)
    }

    @Test
    fun aComputerKeySymbolFinishesTheWordOnce() {
        val hyphen = finishedByComputerKey(ComputerKey.MINUS)
        assertEquals(listOf("hello-"), hyphen.committed)
        assertEquals("", hyphen.composing)

        val bang = finishedByComputerKey(ComputerKey.BANG)
        assertEquals(listOf("hello!"), bang.committed)
        assertEquals("", bang.composing)
    }

    @Test
    fun backspaceAtTheStartOfAWordDoesNotDeleteFieldText() {
        val field = Field().apply { text.append("xyz") }
        val controller = controller(field)
        openLatinWord(controller, "ab")
        parkCaret(controller, 0)

        controller.onKey(KeyAction.Backspace)

        assertFalse(controller.backspaceProgress())
        assertEquals(0, field.deleteCalls)
        assertEquals("xyz", field.text.toString())
        assertEquals("ab", field.composing)
    }

    @Test
    fun revertingASpaceRestoresTheWord() {
        val field = Field()
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states)
        openLatinWord(controller, "hello")

        controller.onKey(KeyAction.Space)
        assertEquals(listOf("hello "), field.committed)
        assertEquals("", field.composing)

        controller.revertLastSpaceTap()

        assertEquals(1, field.deleteCalls)
        assertEquals("hello ".length, field.deletedBefore)
        assertEquals("hello", field.composing)
        assertTrue(states.last().hasUnconfirmedText)
    }

    @Test
    fun anEnglishCaretAtZeroDoesNotStopZhuyinBackspace() {
        val field = Field().apply { text.append("xyz") }
        val controller = controller(field, engine = ReadyEngine())
        openLatinWord(controller, "ab")
        parkCaret(controller, 0)
        controller.onKey(KeyAction.Backspace)
        assertFalse(controller.backspaceProgress())

        controller.onKey(KeyAction.SetMode(KeyboardMode.ZHUYIN))
        assertEquals(listOf("ab"), field.committed)
        controller.onKey(KeyAction.Bopomofo("ㄅ", '1'))
        val deletesBeforeZhuyin = field.deleteCalls

        controller.onKey(KeyAction.Backspace)

        assertTrue(controller.backspaceProgress())
        assertEquals(deletesBeforeZhuyin, field.deleteCalls)
    }

    private fun finishedByComputerKey(key: ComputerKey): Field {
        val field = Field()
        val controller = controller(field)
        openLatinWord(controller, "hello")
        controller.onKey(KeyAction.Computer(key))
        return field
    }

    private fun openLatinWord(controller: CandidateController, word: String) {
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        type(controller, word)
    }

    private fun type(controller: CandidateController, word: String) {
        for (ch in word) controller.onKey(KeyAction.Character(ch))
    }

    /**
     * A space glide is the only public way to park the caret, and unit-test Paint throws.
     * The caret itself is what backspace reads.
     */
    private fun parkCaret(controller: CandidateController, caret: Int) {
        val session = CandidateController::class.java.getDeclaredField("english").apply {
            isAccessible = true
        }.get(controller)
        val field = session.javaClass.getDeclaredField("caret").apply { isAccessible = true }
        field.setInt(session, caret)
    }

    private class Field : InvocationHandler {
        val committed = ArrayList<String>()
        val text = StringBuilder()
        var composing = ""
        var deleteCalls = 0
        var deletedBefore = 0

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
        states: MutableList<ImeUiState> = mutableListOf(),
        engine: ZhuyinEngine = ReadyEngine(),
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
            zhuyinEngine = engine,
        )
    }
}
