package com.fskeyboard.ime.candidate

import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.keyboard.ComputerKey
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.session.dispatchedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ComputerKeyFieldTest {
    @Test
    fun navigationKeysAreEventsOnEveryFieldWithOrWithoutALatch() {
        val keys = listOf(
            ComputerKey.UP,
            ComputerKey.DOWN,
            ComputerKey.LEFT,
            ComputerKey.RIGHT,
            ComputerKey.HOME,
            ComputerKey.END,
            ComputerKey.PAGE_UP,
            ComputerKey.PAGE_DOWN,
            ComputerKey.ESC,
        )
        val fields = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_NULL,
        )
        val extra = mapOf(
            ComputerKey.LEFT to ComputerKey.ALT,
            ComputerKey.END to ComputerKey.META,
            ComputerKey.PAGE_UP to ComputerKey.FN,
        )
        for (inputType in fields) {
            val handler = RecordingConnection()
            val controller = controller(handler, inputType)
            for (key in keys) {
                val latches = ArrayList<ComputerKey?>()
                latches += null
                latches += ComputerKey.CTRL
                extra[key]?.let { latches += it }
                for (latch in latches) {
                    handler.reset()
                    if (latch != null) controller.onKey(KeyAction.Computer(latch))
                    controller.onKey(KeyAction.Computer(key))
                    val label = "$key type=$inputType latch=$latch"
                    assertDownUp(handler.events, key.keyCode, latch?.metaState ?: 0, label)
                    assertTrue("$label committed", handler.committed.isEmpty())
                    assertTrue("$label deleted", handler.surrounding.isEmpty())
                }
            }
        }
    }

    @Test
    fun unmodifiedBackspaceDeletesSurroundingTextWhenItChanges() {
        val handler = RecordingConnection(before = "字", surroundingChanges = true)
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)

        controller.onKey(KeyAction.Backspace)

        assertEquals(listOf(1 to 0), handler.surrounding)
        assertTrue(handler.events.isEmpty())
        assertTrue(handler.committed.isEmpty())
    }

    @Test
    fun unmodifiedBackspaceSendsDeleteWhenSurroundingTextStays() {
        val handler = RecordingConnection(before = "前\n")
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)

        controller.onKey(KeyAction.Backspace)

        assertEquals(listOf(1 to 0), handler.surrounding)
        assertDownUp(handler.events, KeyEvent.KEYCODE_DEL, 0, "unchanged")
        assertTrue(handler.committed.isEmpty())
    }

    @Test
    fun unmodifiedForwardDeleteMatchesTheBackspaceSplit() {
        val changed = RecordingConnection(after = "字", surroundingChanges = true)
        controller(changed, InputType.TYPE_CLASS_TEXT).onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))
        assertEquals(listOf(0 to 1), changed.surrounding)
        assertTrue(changed.events.isEmpty())
        assertTrue(changed.committed.isEmpty())

        val stayed = RecordingConnection(after = "後")
        controller(stayed, InputType.TYPE_CLASS_TEXT).onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))
        assertEquals(listOf(0 to 1), stayed.surrounding)
        assertDownUp(stayed.events, KeyEvent.KEYCODE_FORWARD_DEL, 0, "unchanged forward")
        assertTrue(stayed.committed.isEmpty())
    }

    @Test
    fun otherReadableClassesStillDeleteThroughTheTextApi() {
        val classes = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        )
        for (inputType in classes) {
            val back = RecordingConnection(before = "字", surroundingChanges = true)
            controller(back, inputType).onKey(KeyAction.Backspace)
            assertEquals("$inputType backspace", listOf(1 to 0), back.surrounding)
            assertTrue("$inputType backspace key", back.events.isEmpty())

            val forward = RecordingConnection(after = "字", surroundingChanges = true)
            controller(forward, inputType).onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))
            assertEquals("$inputType forward", listOf(0 to 1), forward.surrounding)
            assertTrue("$inputType forward key", forward.events.isEmpty())
        }
    }

    @Test
    fun anUnreadablePasswordDeletesOneNeighborAndDoesNotSendAKey() {
        val handler = RecordingConnection(readable = false)
        val controller = controller(
            handler,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

        controller.onKey(KeyAction.Backspace)
        controller.onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))

        assertEquals(listOf(1 to 0, 0 to 1), handler.surrounding)
        assertTrue(handler.events.isEmpty())
        assertTrue(handler.committed.isEmpty())
    }

    @Test
    fun aNullFieldSendsDeleteKeysEvenWhenTheTextCannotBeRead() {
        val handler = RecordingConnection(readable = false)
        val controller = controller(handler, InputType.TYPE_NULL)

        controller.onKey(KeyAction.Backspace)
        controller.onKey(KeyAction.Backspace)

        assertTrue(handler.surrounding.isEmpty())
        assertEquals(4, handler.events.size)
        assertDownUp(handler.events.subList(0, 2), KeyEvent.KEYCODE_DEL, 0, "first")
        assertDownUp(handler.events.subList(2, 4), KeyEvent.KEYCODE_DEL, 0, "second")
        assertTrue(controller.backspaceProgress())
        assertTrue(handler.committed.isEmpty())

        val forward = RecordingConnection(readable = false)
        val forwardController = controller(forward, InputType.TYPE_NULL)
        forwardController.onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))
        assertTrue(forward.surrounding.isEmpty())
        assertTrue(forward.committed.isEmpty())
        assertDownUp(forward.events, KeyEvent.KEYCODE_FORWARD_DEL, 0, "forward")
    }

    @Test
    fun aSelectionIsClearedWithoutDeletingANeighbor() {
        for (action in listOf(KeyAction.Backspace, KeyAction.Computer(ComputerKey.FORWARD_DELETE))) {
            val handler = RecordingConnection(selected = "hello")
            val controller = controller(handler, InputType.TYPE_CLASS_TEXT)
            controller.onKey(action)
            assertEquals(listOf(""), handler.committed)
            assertTrue(handler.surrounding.isEmpty())
            assertTrue(handler.events.isEmpty())
        }

        val ranged = RecordingConnection()
        val rangedController = controller(ranged, InputType.TYPE_CLASS_TEXT)
        rangedController.onUpdateSelection(0, 0, 1, 4, -1, -1)
        rangedController.onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))
        assertEquals(listOf(""), ranged.committed)
        assertTrue(ranged.surrounding.isEmpty())
        assertTrue(ranged.events.isEmpty())
    }

    @Test
    fun aLatchedDeleteSendsTheKeyAndDoesNotDeleteSurroundingText() {
        val text = changingField()
        val textController = controller(text, InputType.TYPE_CLASS_TEXT)
        textController.onKey(KeyAction.Computer(ComputerKey.CTRL))
        textController.onKey(KeyAction.Backspace)
        assertTrue(text.surrounding.isEmpty())
        assertTrue(text.committed.isEmpty())
        assertDownUp(text.events, KeyEvent.KEYCODE_DEL, ComputerKey.CTRL.metaState, "ctrl text")

        val none = RecordingConnection(readable = false)
        val nullController = controller(none, InputType.TYPE_NULL)
        nullController.onKey(KeyAction.Computer(ComputerKey.CTRL))
        nullController.onKey(KeyAction.Backspace)
        assertTrue(none.surrounding.isEmpty())
        assertTrue(none.committed.isEmpty())
        assertDownUp(none.events, KeyEvent.KEYCODE_DEL, ComputerKey.CTRL.metaState, "ctrl null")

        val alt = changingField()
        val altController = controller(alt, InputType.TYPE_CLASS_TEXT)
        altController.onKey(KeyAction.Computer(ComputerKey.ALT))
        altController.onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))
        assertTrue(alt.surrounding.isEmpty())
        assertTrue(alt.committed.isEmpty())
        assertDownUp(alt.events, KeyEvent.KEYCODE_FORWARD_DEL, ComputerKey.ALT.metaState, "alt")

        val fn = changingField()
        val fnController = controller(fn, InputType.TYPE_CLASS_TEXT)
        fnController.onKey(KeyAction.Computer(ComputerKey.FN))
        fnController.onKey(KeyAction.Computer(ComputerKey.FORWARD_DELETE))
        assertTrue(fn.surrounding.isEmpty())
        assertTrue(fn.committed.isEmpty())
        assertDownUp(fn.events, KeyEvent.KEYCODE_FORWARD_DEL, ComputerKey.FN.metaState, "fn")
    }

    @Test
    fun fnAndTheDigitRowSendFunctionKeys() {
        val keys = listOf(
            '1' to KeyEvent.KEYCODE_F1,
            '2' to KeyEvent.KEYCODE_F2,
            '3' to KeyEvent.KEYCODE_F3,
            '4' to KeyEvent.KEYCODE_F4,
            '5' to KeyEvent.KEYCODE_F5,
            '6' to KeyEvent.KEYCODE_F6,
            '7' to KeyEvent.KEYCODE_F7,
            '8' to KeyEvent.KEYCODE_F8,
            '9' to KeyEvent.KEYCODE_F9,
            '0' to KeyEvent.KEYCODE_F10,
            'o' to KeyEvent.KEYCODE_F11,
            'O' to KeyEvent.KEYCODE_F11,
            'p' to KeyEvent.KEYCODE_F12,
            'P' to KeyEvent.KEYCODE_F12,
        )
        val handler = RecordingConnection()
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)
        for ((glyph, code) in keys) {
            handler.reset()
            controller.onKey(KeyAction.Computer(ComputerKey.FN))
            controller.onKey(KeyAction.Character(glyph))
            assertDownUp(handler.events, code, 0, "fn+$glyph")
            assertTrue("fn+$glyph committed", handler.committed.isEmpty())
        }
    }

    @Test
    fun aHeldFnStaysForTheNextFunctionKey() {
        val handler = RecordingConnection()
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)
        controller.setModifierHeld(ComputerKey.FN, true)

        controller.onKey(KeyAction.Character('1'))
        assertDownUp(handler.events, KeyEvent.KEYCODE_F1, 0, "held f1")

        handler.reset()
        controller.onKey(KeyAction.Character('p'))
        assertDownUp(handler.events, KeyEvent.KEYCODE_F12, 0, "held f12")
    }

    @Test
    fun ctrlAndFnSendTheFunctionKeyWithCtrl() {
        val handler = RecordingConnection()
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)
        controller.onKey(KeyAction.Computer(ComputerKey.CTRL))
        controller.onKey(KeyAction.Computer(ComputerKey.FN))
        controller.onKey(KeyAction.Character('4'))
        assertDownUp(handler.events, KeyEvent.KEYCODE_F4, ComputerKey.CTRL.metaState, "ctrl+fn+4")
        assertTrue(handler.committed.isEmpty())
    }

    @Test
    fun fnAndAnotherLetterStillSendsThatLetter() {
        val handler = RecordingConnection()
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)
        controller.onKey(KeyAction.Computer(ComputerKey.FN))
        controller.onKey(KeyAction.Character('a'))
        assertDownUp(handler.events, KeyEvent.KEYCODE_A, ComputerKey.FN.metaState, "fn+a")
        assertTrue(handler.committed.isEmpty())
    }

    @Test
    fun ctrlAndADigitIsNotAFunctionKey() {
        val handler = RecordingConnection()
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)
        controller.onKey(KeyAction.Computer(ComputerKey.CTRL))
        controller.onKey(KeyAction.Character('1'))
        assertDownUp(handler.events, KeyEvent.KEYCODE_1, ComputerKey.CTRL.metaState, "ctrl+1")
        assertTrue(handler.committed.isEmpty())
    }

    private fun changingField(): RecordingConnection {
        return RecordingConnection(before = "字", after = "字", surroundingChanges = true)
    }

    private fun assertDownUp(events: List<KeyEvent>, keyCode: Int, meta: Int, label: String) {
        assertEquals("$label events", 2, events.size)
        val down = checkNotNull(dispatchedKey(events[0])) { "$label down" }
        val up = checkNotNull(dispatchedKey(events[1])) { "$label up" }
        assertEquals("$label action down", KeyEvent.ACTION_DOWN, down.action)
        assertEquals("$label action up", KeyEvent.ACTION_UP, up.action)
        assertEquals("$label code down", keyCode, down.keyCode)
        assertEquals("$label code up", keyCode, up.keyCode)
        assertEquals("$label meta down", meta, down.meta)
        assertEquals("$label meta up", meta, up.meta)
        assertEquals("$label device down", KeyCharacterMap.VIRTUAL_KEYBOARD, down.deviceId)
        assertEquals("$label device up", KeyCharacterMap.VIRTUAL_KEYBOARD, up.deviceId)
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        assertEquals("$label flags down", flags, down.flags)
        assertEquals("$label flags up", flags, up.flags)
    }

    private class RecordingConnection(
        var before: String? = "字",
        var after: String? = "字",
        var selected: String? = null,
        private val readable: Boolean = true,
        private val surroundingChanges: Boolean = false,
    ) : InvocationHandler {
        val events = ArrayList<KeyEvent>()
        val committed = ArrayList<String>()
        val surrounding = ArrayList<Pair<Int, Int>>()

        fun reset() {
            events.clear()
            committed.clear()
            surrounding.clear()
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "sendKeyEvent" -> {
                    events += args?.get(0) as KeyEvent
                    true
                }
                "commitText" -> {
                    committed += args?.get(0).toString()
                    true
                }
                "deleteSurroundingText" -> {
                    val beforeCount = (args?.get(0) as? Number)?.toInt() ?: 0
                    val afterCount = (args?.get(1) as? Number)?.toInt() ?: 0
                    surrounding += beforeCount to afterCount
                    if (surroundingChanges) {
                        if (beforeCount > 0 && !before.isNullOrEmpty()) {
                            val current = before!!
                            before = current.dropLast(minOf(beforeCount, current.length))
                        }
                        if (afterCount > 0 && !after.isNullOrEmpty()) {
                            val current = after!!
                            after = current.drop(minOf(afterCount, current.length))
                        }
                    }
                    true
                }
                "getTextBeforeCursor" -> if (readable) before else null
                "getTextAfterCursor" -> if (readable) after else null
                "getSelectedText" -> selected
                "finishComposingText" -> true
                "setComposingText" -> true
                "toString" -> "RecordingConnection"
                "hashCode" -> 1
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }
    }

    private fun controller(handler: RecordingConnection, inputType: Int): CandidateController {
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            handler,
        ) as InputConnection
        val context = object : android.content.ContextWrapper(null) {}
        val info = EditorInfo().apply { this.inputType = inputType }
        return CandidateController(
            context = context,
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = { info },
            onUi = {},
            post = { it.run() },
        )
    }
}
