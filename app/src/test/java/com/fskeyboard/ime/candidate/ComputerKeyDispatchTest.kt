package com.fskeyboard.ime.candidate

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.ComputerKey
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ComputerKeyDispatchTest {
    private class RecordingConnection : InvocationHandler {
        val events = ArrayList<KeyEvent>()

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "sendKeyEvent" -> {
                    events += args?.get(0) as KeyEvent
                    true
                }
                "finishComposingText" -> true
                "toString" -> "RecordingConnection"
                "hashCode" -> 1
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }
    }

    @Test
    fun ctrlWaitsAndTheNextLetterIsSentWithCtrl() {
        val handler = RecordingConnection()
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            handler,
        ) as InputConnection
        val context = object : android.content.ContextWrapper(null) {}
        val lexicon = object : ZhuyinLexicon {
            override val status = com.fskeyboard.ime.engine.EngineStatus.READY
            override val statusDetail = ""
            override val isReady = true
            override fun start(context: Context, onReady: () -> Unit) = Unit
            override fun close() = Unit
            override fun lookup(queries: List<String>, before: String) = emptyList<Candidate>()
            override fun learn(phrases: List<Pair<String, String>>) = Unit
        }
        val armed = ArrayList<Set<ComputerKey>>()
        val controller = CandidateController(
            context = context,
            lexicon = lexicon,
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = { EditorInfo() },
            onUi = {},
            post = { it.run() },
        )
        controller.onComputerModifiers = { armed += it }

        controller.onKey(KeyAction.Computer(ComputerKey.CTRL))
        assertEquals(listOf(setOf(ComputerKey.CTRL)), armed)
        assertTrue(handler.events.isEmpty())

        controller.onKey(KeyAction.Character('c'))
        assertEquals(2, handler.events.size)
        assertEquals(listOf(setOf(ComputerKey.CTRL), emptySet()), armed)
    }

    @Test
    fun escapeIsSentImmediately() {
        val handler = RecordingConnection()
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            handler,
        ) as InputConnection
        val context = object : android.content.ContextWrapper(null) {}
        val lexicon = object : ZhuyinLexicon {
            override val status = com.fskeyboard.ime.engine.EngineStatus.READY
            override val statusDetail = ""
            override val isReady = true
            override fun start(context: Context, onReady: () -> Unit) = Unit
            override fun close() = Unit
            override fun lookup(queries: List<String>, before: String) = emptyList<Candidate>()
            override fun learn(phrases: List<Pair<String, String>>) = Unit
        }
        val controller = CandidateController(
            context = context,
            lexicon = lexicon,
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = { EditorInfo() },
            onUi = {},
            post = { it.run() },
        )
        controller.onKey(KeyAction.Computer(ComputerKey.ESC))
        assertEquals(2, handler.events.size)
    }
}
