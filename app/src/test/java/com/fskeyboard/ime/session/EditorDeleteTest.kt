package com.fskeyboard.ime.session

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class EditorDeleteTest {
    @Test
    fun aNewlineThatSurvivesSurroundingDeleteIsRemovedByTheDeleteKey() {
        val field = LineField("前\n")
        val session = EditorSession { field.connection() }

        assertTrue(session.deleteOne())

        assertEquals("前", field.text)
        assertEquals(1, field.surroundingDeletes)
        assertEquals(2, field.deleteKeys)
    }

    @Test
    fun aSecondPressAtTheSameUnchangedTextDoesNotCallSurroundingDeleteAgain() {
        val field = LineField("前\n", acceptDeleteKey = false)
        val session = EditorSession { field.connection() }

        assertFalse(session.deleteOne())
        assertFalse(session.deleteOne())

        assertEquals("前\n", field.text)
        assertEquals(1, field.surroundingDeletes)
        assertEquals(2, field.deleteKeys)
    }

    @Test
    fun aCharacterRemovedBySurroundingDeleteDoesNotAlsoSendTheDeleteKey() {
        val field = LineField("字", surroundingRemoves = true)
        val session = EditorSession { field.connection() }

        assertTrue(session.deleteOne())

        assertEquals("", field.text)
        assertEquals(1, field.surroundingDeletes)
        assertEquals(0, field.deleteKeys)
    }

    @Test
    fun anUnreadableFieldStillDeletesOneSurroundingCharacter() {
        val field = LineField("字", readable = false)
        val session = EditorSession { field.connection() }

        assertTrue(session.deleteOne())

        assertEquals(1, field.surroundingDeletes)
        assertEquals(0, field.deleteKeys)
    }

    @Test
    fun aCharacterRemovedByForwardDeleteDoesNotAlsoSendTheDeleteKey() {
        val field = LineField("字", surroundingRemoves = true)
        val session = EditorSession { field.connection() }

        assertTrue(session.deleteForward())

        assertEquals("", field.text)
        assertEquals(1, field.surroundingDeletes)
        assertEquals(0, field.deletedBefore)
        assertEquals(1, field.deletedAfter)
        assertEquals(0, field.deleteKeys)
    }

    @Test
    fun anUnreadableFieldStillDeletesOneCharacterAfterTheCaret() {
        val field = LineField("字", readable = false)
        val session = EditorSession { field.connection() }

        assertTrue(session.deleteForward())

        assertEquals(1, field.surroundingDeletes)
        assertEquals(0, field.deletedBefore)
        assertEquals(1, field.deletedAfter)
        assertEquals(0, field.deleteKeys)
    }

    @Test
    fun aSecondForwardDeleteAtTheSameUnchangedTextDoesNotCallSurroundingDeleteAgain() {
        val field = LineField("後", acceptDeleteKey = false)
        val session = EditorSession { field.connection() }

        assertFalse(session.deleteForward())
        assertFalse(session.deleteForward())

        assertEquals("後", field.text)
        assertEquals(1, field.surroundingDeletes)
        assertEquals(0, field.deletedBefore)
        assertEquals(1, field.deletedAfter)
        assertEquals(2, field.deleteKeys)
    }

    private class LineField(
        start: String,
        private val acceptDeleteKey: Boolean = true,
        private val readable: Boolean = true,
        private val surroundingRemoves: Boolean = false,
    ) : InvocationHandler {
        var text: String = start
        var surroundingDeletes: Int = 0
        var deletedBefore: Int = 0
        var deletedAfter: Int = 0
        var deleteKeys: Int = 0

        fun connection(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java),
                this,
            ) as InputConnection
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "getTextBeforeCursor" -> {
                    if (!readable) return null
                    val limit = (args?.get(0) as? Number)?.toInt() ?: text.length
                    if (limit >= text.length) text else text.substring(text.length - limit)
                }
                "getTextAfterCursor" -> {
                    if (!readable) return null
                    val limit = (args?.get(0) as? Number)?.toInt() ?: text.length
                    if (limit >= text.length) text else text.substring(0, limit)
                }
                "deleteSurroundingText" -> {
                    surroundingDeletes += 1
                    val beforeCount = (args?.get(0) as? Number)?.toInt() ?: 0
                    val afterCount = (args?.get(1) as? Number)?.toInt() ?: 0
                    deletedBefore += beforeCount
                    deletedAfter += afterCount
                    if (surroundingRemoves && text.isNotEmpty()) {
                        text = if (afterCount > 0) text.drop(1) else text.dropLast(1)
                    }
                    true
                }
                "sendKeyEvent" -> {
                    if (deleteKeys % 2 == 0 && acceptDeleteKey && text.isNotEmpty()) {
                        text = text.dropLast(1)
                    }
                    deleteKeys += 1
                    true
                }
                "getSelectedText" -> null
                "toString" -> "LineField"
                "hashCode" -> 1
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }
    }
}
