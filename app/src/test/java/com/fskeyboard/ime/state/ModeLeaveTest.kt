package com.fskeyboard.ime.state

import android.content.ContextWrapper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.candidate.CandidateController
import com.fskeyboard.ime.candidate.ImeUiState
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ModeLeaveTest {
    @Test
    fun latinAndSymbolPagesKeepEnglish() {
        assertLeave(KeyboardMode.LATIN, KeyboardMode.SYMBOLS, CompositionLeave.KEEP, CompositionLeave.KEEP)
        assertLeave(KeyboardMode.LATIN, KeyboardMode.SYMBOLS_MORE, CompositionLeave.KEEP, CompositionLeave.KEEP)
        assertLeave(KeyboardMode.SYMBOLS, KeyboardMode.LATIN, CompositionLeave.KEEP, CompositionLeave.KEEP)
        assertLeave(KeyboardMode.SYMBOLS_MORE, KeyboardMode.LATIN, CompositionLeave.KEEP, CompositionLeave.KEEP)
    }

    @Test
    fun symbolPagesCommitTheEnglishWord() {
        assertLeave(
            KeyboardMode.SYMBOLS,
            KeyboardMode.SYMBOLS_MORE,
            CompositionLeave.COMMIT_TYPED,
            CompositionLeave.KEEP,
        )
        assertLeave(
            KeyboardMode.SYMBOLS_MORE,
            KeyboardMode.SYMBOLS,
            CompositionLeave.COMMIT_TYPED,
            CompositionLeave.KEEP,
        )
    }

    @Test
    fun otherSwitchesCommitEnglishAndLeavingZhuyinCommitsZhuyin() {
        assertLeave(KeyboardMode.LATIN, KeyboardMode.ZHUYIN, CompositionLeave.COMMIT_TYPED, CompositionLeave.KEEP)
        assertLeave(KeyboardMode.SYMBOLS, KeyboardMode.ZHUYIN, CompositionLeave.COMMIT_TYPED, CompositionLeave.KEEP)
        assertLeave(KeyboardMode.ZHUYIN, KeyboardMode.LATIN, CompositionLeave.COMMIT_TYPED, CompositionLeave.COMMIT_TYPED)
        assertLeave(KeyboardMode.ZHUYIN, KeyboardMode.SYMBOLS, CompositionLeave.COMMIT_TYPED, CompositionLeave.COMMIT_TYPED)
        assertLeave(
            KeyboardMode.ZHUYIN,
            KeyboardMode.SYMBOLS_MORE,
            CompositionLeave.COMMIT_TYPED,
            CompositionLeave.COMMIT_TYPED,
        )
    }

    @Test
    fun numberAndTheSameModeAreKeep() {
        for (mode in KeyboardMode.values()) {
            assertLeave(mode, mode, CompositionLeave.KEEP, CompositionLeave.KEEP)
            assertLeave(mode, KeyboardMode.NUMBER, CompositionLeave.KEEP, CompositionLeave.KEEP)
            assertLeave(KeyboardMode.NUMBER, mode, CompositionLeave.KEEP, CompositionLeave.KEEP)
        }
    }

    @Test
    fun setModeDoesNotApplyNumberOrTheSameMode() {
        val states = mutableListOf<ImeUiState>()
        val controller = controller(states)
        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        for (ch in "hello") controller.onKey(KeyAction.Character(ch))
        val open = states.last()
        assertEquals(KeyboardMode.LATIN, open.mode)
        assertTrue(open.candidates.candidates.any { it.text == "hello" })

        controller.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        assertEquals(KeyboardMode.LATIN, states.last().mode)
        assertTrue(states.last().candidates.candidates.any { it.text == "hello" })
        assertTrue(states.last().hasUnconfirmedText)

        controller.onKey(KeyAction.SetMode(KeyboardMode.NUMBER))
        assertEquals(KeyboardMode.LATIN, states.last().mode)
        assertEquals(KeyboardMode.LATIN, states.last().base)
        assertTrue(states.last().candidates.candidates.any { it.text == "hello" })
        assertTrue(states.last().hasUnconfirmedText)
    }

    private fun assertLeave(
        from: KeyboardMode,
        to: KeyboardMode,
        english: CompositionLeave,
        zhuyin: CompositionLeave,
    ) {
        assertEquals(LeaveDecision(english, zhuyin), modeLeave(from, to))
    }

    private fun controller(states: MutableList<ImeUiState>): CandidateController {
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            InvocationHandler { _, method: Method, args: Array<out Any>? ->
                when (method.name) {
                    "setComposingText", "finishComposingText", "commitText", "deleteSurroundingText" -> true
                    "getTextBeforeCursor" -> ""
                    "getSelectedText" -> null
                    "toString" -> "field"
                    "hashCode" -> 1
                    "equals" -> false
                    else -> null
                }
            },
        ) as InputConnection
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        return CandidateController(
            context = object : ContextWrapper(null) {},
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = { info },
            onUi = { states.add(it) },
            post = { it.run() },
        )
    }
}
