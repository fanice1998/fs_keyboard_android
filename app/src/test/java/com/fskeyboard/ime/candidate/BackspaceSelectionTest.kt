package com.fskeyboard.ime.candidate

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class BackspaceSelectionTest {
    private class TestInputConnection : InvocationHandler {
        var selectedText: String? = null
        var committedText: String? = null
        var composingText: String? = null
        var deletedBefore: Int = 0
        var deletedAfter: Int = 0

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "getSelectedText" -> selectedText
                "setComposingText" -> {
                    composingText = args?.get(0)?.toString()
                    true
                }
                "finishComposingText" -> true
                "commitText" -> {
                    committedText = args?.get(0)?.toString()
                    true
                }
                "deleteSurroundingText" -> {
                    deletedBefore = (args?.get(0) as? Number)?.toInt() ?: 0
                    deletedAfter = (args?.get(1) as? Number)?.toInt() ?: 0
                    true
                }
                "toString" -> "TestInputConnection"
                "hashCode" -> 1
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }
    }

    private fun createConnection(handler: TestInputConnection): InputConnection {
        return Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            handler,
        ) as InputConnection
    }

    private fun createController(
        connection: InputConnection,
        editorInfo: () -> EditorInfo? = { EditorInfo() },
    ): CandidateController {
        // Dummy Context placeholder (methods not called during backspace test)
        val dummyContext = Proxy.newProxyInstance(
            Context::class.java.classLoader,
            arrayOf(),
        ) { _, _, _ -> null }

        // We can pass null or a dummy via reflection since Context is an abstract class
        // In Kotlin/JVM, passing an uninitialized or mocked instance or using objenesis / Unsafe
        // But simpler: create an anonymous subclass of android.content.ContextWrapper if needed,
        // or let's use an empty subclass of ContextWrapper(null).
        val context = object : android.content.ContextWrapper(null) {}

        val dummyLexicon = object : ZhuyinLexicon {
            override val status = com.fskeyboard.ime.engine.EngineStatus.READY
            override val statusDetail = ""
            override val isReady = true
            override fun start(context: Context, onReady: () -> Unit) = Unit
            override fun close() = Unit
            override fun lookup(queries: List<String>, before: String) = emptyList<Candidate>()
            override fun learn(phrases: List<Pair<String, String>>) = Unit
        }

        return CandidateController(
            context = context,
            lexicon = dummyLexicon,
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = editorInfo,
            onUi = {},
            post = { it.run() },
        )
    }

    @Test
    fun backspaceWithSelectedTextDeletesTheSelectionViaCommitText() {
        val handler = TestInputConnection().apply {
            selectedText = "hello world"
        }
        val connection = createConnection(handler)
        val controller = createController(connection)

        controller.onKey(KeyAction.Backspace)

        assertEquals("", handler.committedText)
        assertEquals(0, handler.deletedBefore)
    }

    @Test
    fun backspaceWithoutSelectionDeletesOneSurroundingCharacter() {
        val handler = TestInputConnection().apply {
            selectedText = null
        }
        val connection = createConnection(handler)
        val controller = createController(connection)

        controller.onKey(KeyAction.Backspace)

        assertNull(handler.committedText)
        assertEquals(1, handler.deletedBefore)
        assertEquals(0, handler.deletedAfter)
    }

    @Test
    fun backspaceWithSelectionRangeFromUpdateSelectionDeletesSelection() {
        val handler = TestInputConnection().apply {
            selectedText = null // getSelectedText returns null, but selection coordinates exist
        }
        val connection = createConnection(handler)
        val controller = createController(connection)

        controller.onUpdateSelection(
            oldSelStart = 0,
            oldSelEnd = 0,
            newSelStart = 3,
            newSelEnd = 8,
            candidatesStart = -1,
            candidatesEnd = -1,
        )

        controller.onKey(KeyAction.Backspace)

        assertEquals("", handler.committedText)
        assertEquals(0, handler.deletedBefore)
    }

    @Test
    fun openingAFieldWhoseTextIsSelectedDoesNotDeleteIt() {
        val handler = TestInputConnection()
        val info = EditorInfo().apply {
            initialSelStart = 0
            initialSelEnd = 18
        }
        val controller = createController(createConnection(handler)) { info }

        controller.onStartInput(restarting = false)

        assertNull(handler.composingText)
        assertNull(handler.committedText)
        assertEquals(0, handler.deletedBefore)
    }
}
