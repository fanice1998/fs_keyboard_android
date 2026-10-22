package com.fskeyboard.ime.candidate

import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.NumberPad
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class NumberKeyboardTest {
    @Test
    fun aNumberFieldOpensTheDigitPadAndTheNextFieldRestoresLetters() {
        val writes = mutableListOf<KeyboardMode>()
        val states = mutableListOf<ImeUiState>()
        val committed = mutableListOf<String>()
        var numeric = true
        val controller = controller(
            states = states,
            committed = committed,
            editorInfo = {
                EditorInfo().apply {
                    inputType = if (numeric) {
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    } else {
                        InputType.TYPE_CLASS_TEXT
                    }
                }
            },
            letterMode = { KeyboardMode.ZHUYIN },
            onLetterMode = { writes.add(it) },
            clipboard = { ClipOffer(label = "9021", text = "9021") },
        )

        controller.onStartInput(restarting = false)
        assertEquals(KeyboardMode.NUMBER, states.last().mode)
        assertEquals(KeyboardMode.ZHUYIN, states.last().base)
        assertEquals(NumberPad(decimal = true), states.last().numberPad)
        assertTrue(states.last().candidates.candidates.isEmpty())
        assertTrue(writes.isEmpty())

        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        assertEquals(KeyboardMode.NUMBER, states.last().mode)
        controller.onKey(KeyAction.Character('5'))
        assertEquals(listOf("5"), committed)

        numeric = false
        controller.onStartInput(restarting = false)
        assertEquals(KeyboardMode.ZHUYIN, states.last().mode)
        assertEquals(KeyboardMode.ZHUYIN, states.last().base)
        assertEquals(NumberPad.NONE, states.last().numberPad)
        assertEquals(listOf(CandidateSource.CLIPBOARD), states.last().candidates.candidates.map { it.source })
        assertTrue(writes.isEmpty())
    }

    @Test
    fun aLetterKeyboardCannotOpenTheDigitPad() {
        val states = mutableListOf<ImeUiState>()
        val controller = controller(
            states = states,
            letterMode = { KeyboardMode.LATIN },
        )
        controller.onStartInput(restarting = false)
        controller.onKey(KeyAction.SetMode(KeyboardMode.NUMBER))
        assertEquals(KeyboardMode.LATIN, states.last().mode)
        assertEquals(KeyboardMode.LATIN, states.last().base)
        assertEquals(NumberPad.NONE, states.last().numberPad)
    }

    @Test
    fun aPhoneFieldUsesTheSamePadAndKeepsTheSavedLetters() {
        val states = mutableListOf<ImeUiState>()
        val controller = controller(
            states = states,
            editorInfo = {
                EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE }
            },
            letterMode = { KeyboardMode.LATIN },
        )
        controller.onStartInput(restarting = false)
        assertEquals(KeyboardMode.NUMBER, states.last().mode)
        assertEquals(KeyboardMode.LATIN, states.last().base)
        assertEquals(NumberPad.NONE, states.last().numberPad)
    }
}

private fun controller(
    states: MutableList<ImeUiState>,
    committed: MutableList<String> = mutableListOf(),
    editorInfo: () -> EditorInfo? = { EditorInfo() },
    letterMode: () -> KeyboardMode = { KeyboardMode.ZHUYIN },
    onLetterMode: (KeyboardMode) -> Unit = {},
    clipboard: () -> ClipOffer? = { null },
): CandidateController {
    val handler = InvocationHandler { _: Any?, method: Method, args: Array<out Any>? ->
        when (method.name) {
            "commitText" -> {
                committed.add(args?.get(0)?.toString().orEmpty())
                true
            }
            "finishComposingText", "setComposingText", "deleteSurroundingText" -> true
            "getSelectedText", "getTextBeforeCursor" -> null
            "toString" -> "field"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    }
    val connection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java),
        handler,
    ) as InputConnection
    val lexicon = object : ZhuyinLexicon {
        override val status = com.fskeyboard.ime.engine.EngineStatus.READY
        override val statusDetail = ""
        override val isReady = true
        override fun start(context: Context, onReady: () -> Unit) = Unit
        override fun close() = Unit
        override fun lookup(queries: List<String>, before: String) = emptyList<Candidate>()
        override fun learn(phrases: List<Pair<String, String>>) = Unit
    }
    return CandidateController(
        context = object : ContextWrapper(null) {},
        lexicon = lexicon,
        pipeline = CandidatePipeline(),
        connection = { connection },
        editorInfo = editorInfo,
        onUi = { states.add(it) },
        post = { it.run() },
        letterMode = letterMode,
        clipboard = clipboard,
    ).also { it.onLetterMode = onLetterMode }
}
