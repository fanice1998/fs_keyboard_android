package com.fskeyboard.ime.candidate

import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.keyboard.ComputerKey
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.ShiftLock
import com.fskeyboard.ime.keyboard.ShiftMode
import com.fskeyboard.ime.keyboard.applyingShift
import com.fskeyboard.ime.keyboard.consumesOneShotShift
import com.fskeyboard.ime.readProjectFile
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.session.dispatchedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ComputerKeyTabTest {
    private class RecordingConnection : InvocationHandler {
        val events = ArrayList<KeyEvent>()
        val committed = ArrayList<String>()

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
                "finishComposingText" -> true
                "toString" -> "RecordingConnection"
                "hashCode" -> 1
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }
    }

    @Test
    fun shiftOffSendsTabWithoutShiftMeta() {
        for ((name, inputType) in fields()) {
            val shift = ShiftLock()
            val handler = RecordingConnection()
            val controller = controller(handler, inputType)
            controller.onKey(stampedTab(shift))
            assertEquals(ShiftMode.OFF, shift.mode)
            assertTab(handler, name, shift = false)
        }
    }

    @Test
    fun latchedCtrlSendsTabWithoutShiftMeta() {
        for ((name, inputType) in fields()) {
            val shift = ShiftLock()
            val handler = RecordingConnection()
            val controller = controller(handler, inputType)
            controller.onKey(KeyAction.Computer(ComputerKey.CTRL))
            controller.onKey(stampedTab(shift))
            assertEquals(ShiftMode.OFF, shift.mode)
            assertTab(handler, name, shift = false, ctrl = true)
        }
    }

    @Test
    fun oneShotShiftSendsTabThenClears() {
        for ((name, inputType) in fields()) {
            val shift = ShiftLock()
            shift.onShift()
            assertEquals(ShiftMode.ONCE, shift.mode)
            val handler = RecordingConnection()
            val controller = controller(handler, inputType)
            val sending = stampedTab(shift)
            controller.onKey(sending)
            spendOneShot(shift, sending)
            assertEquals("$name shift", ShiftMode.OFF, shift.mode)
            assertTab(handler, name, shift = true)
        }
    }

    @Test
    fun capsLockSendsTabAndStaysLocked() {
        for ((name, inputType) in fields()) {
            val shift = ShiftLock()
            shift.onShift()
            shift.onShift()
            assertEquals(ShiftMode.LOCKED, shift.mode)
            val handler = RecordingConnection()
            val controller = controller(handler, inputType)
            val sending = stampedTab(shift)
            controller.onKey(sending)
            spendOneShot(shift, sending)
            assertEquals("$name caps", ShiftMode.LOCKED, shift.mode)
            assertTab(handler, name, shift = true)
        }
    }

    @Test
    fun oneShotTabKeepsLatchedAlt() {
        val shift = ShiftLock()
        shift.onShift()
        assertEquals(ShiftMode.ONCE, shift.mode)
        val handler = RecordingConnection()
        val controller = controller(handler, InputType.TYPE_CLASS_TEXT)
        controller.onKey(KeyAction.Computer(ComputerKey.ALT))
        val sending = stampedTab(shift)
        controller.onKey(sending)
        spendOneShot(shift, sending)
        assertEquals(ShiftMode.OFF, shift.mode)
        assertTab(handler, "alt", shift = true, alt = true)
    }

    @Test
    fun dispatchStampsShiftOntoTheActionOnKeyReceives() {
        val body = dispatchBody()
        assertTrue(body.contains("applyingShift(shiftLock.mode)"))
        val keepShift = "val keepShift = action is KeyAction.Character && repeating == action"
        assertTrue(body.contains(keepShift))
        assertFalse(body.replace(keepShift, "").contains("KeyAction.Character"))
        assertFalse(Regex("""onKey\(\s*action\s*\)""").containsMatchIn(body))
        val sent = calls(body, "onKey")
        assertTrue(sent.isNotEmpty())
        for (call in sent) {
            val expr = call.argument.trim()
            assertFalse("onKey($expr) drops the shift", expr == "action")
            val inline = expr.contains("applyingShift(shiftLock.mode)")
            val binding = if (inline) {
                null
            } else {
                Regex(
                    """(?:val|var)\s+${Regex.escape(expr)}\s*=\s*[^\n]*applyingShift\(shiftLock\.mode\)""",
                ).find(body)
            }
            assertTrue("onKey($expr) is not the stamped action", inline || binding != null)
            if (!inline) assertTrue(binding!!.range.first < call.at)
        }
        val spends = calls(body, "shiftLock.onCharacter")
        assertTrue(spends.isNotEmpty())
        for (call in spends) {
            val arg = call.argument.trim()
            assertTrue(arg == "keepShift" || arg == "keepOnce = keepShift")
            val before = body.substring(0, call.at)
            val ifAt = Regex("""\bif\b""").findAll(before).lastOrNull()?.range?.first
            assertTrue(ifAt != null)
            val gate = body.substring(ifAt!!, call.at)
            assertTrue(gate.contains("consumesOneShotShift()"))
            assertFalse(gate.contains("KeyAction.Character"))
        }
    }

    private fun stampedTab(shift: ShiftLock): KeyAction {
        val sending = KeyAction.Computer(ComputerKey.TAB).applyingShift(shift.mode)
        assertTrue(sending is KeyAction.Computer)
        assertEquals(shift.mode, (sending as KeyAction.Computer).shift)
        return sending
    }

    /** The view rule: a letter or Tab spends a one-shot. A repeating letter passes keepOnce. */
    private fun spendOneShot(shift: ShiftLock, sending: KeyAction) {
        if (sending.consumesOneShotShift()) shift.onCharacter(keepOnce = false)
    }

    private fun assertTab(
        handler: RecordingConnection,
        label: String,
        shift: Boolean,
        ctrl: Boolean = false,
        alt: Boolean = false,
    ) {
        assertFalse("$label committed a tab", handler.committed.any { '\t' in it })
        // KeyEvent getters return 0 on the JVM. dispatchedKey reads the sent event.
        val events = handler.events.map { dispatchedKey(it) ?: error("$label missing event") }
        assertEquals("$label down/up", 2, events.size)
        assertEquals("$label down", KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals("$label up", KeyEvent.ACTION_UP, events[1].action)
        for (event in events) {
            assertEquals("$label key", KeyEvent.KEYCODE_TAB, event.keyCode)
            assertEquals("$label device", KeyCharacterMap.VIRTUAL_KEYBOARD, event.deviceId)
            assertTrue("$label soft", event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0)
            assertTrue("$label touch", event.flags and KeyEvent.FLAG_KEEP_TOUCH_MODE != 0)
            if (shift) {
                assertTrue("$label shift", event.meta and KeyEvent.META_SHIFT_ON != 0)
                assertTrue("$label shift left", event.meta and KeyEvent.META_SHIFT_LEFT_ON != 0)
            } else {
                assertEquals("$label no shift", 0, event.meta and KeyEvent.META_SHIFT_ON)
                assertEquals("$label no shift left", 0, event.meta and KeyEvent.META_SHIFT_LEFT_ON)
            }
            if (ctrl) {
                assertTrue("$label ctrl", event.meta and KeyEvent.META_CTRL_ON != 0)
                assertTrue("$label ctrl left", event.meta and KeyEvent.META_CTRL_LEFT_ON != 0)
            } else {
                assertEquals("$label no ctrl", 0, event.meta and KeyEvent.META_CTRL_ON)
                assertEquals("$label no ctrl left", 0, event.meta and KeyEvent.META_CTRL_LEFT_ON)
            }
            if (alt) {
                assertTrue("$label alt", event.meta and KeyEvent.META_ALT_ON != 0)
                assertTrue("$label alt left", event.meta and KeyEvent.META_ALT_LEFT_ON != 0)
            } else {
                assertEquals("$label no alt", 0, event.meta and KeyEvent.META_ALT_ON)
                assertEquals("$label no alt left", 0, event.meta and KeyEvent.META_ALT_LEFT_ON)
            }
        }
    }

    private fun fields(): List<Pair<String, Int>> {
        return listOf(
            "text" to InputType.TYPE_CLASS_TEXT,
            "multiline" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE),
            "password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            "email" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            "uri" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            "number" to InputType.TYPE_CLASS_NUMBER,
            "phone" to InputType.TYPE_CLASS_PHONE,
            "datetime" to InputType.TYPE_CLASS_DATETIME,
            "null" to InputType.TYPE_NULL,
        )
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

    private fun dispatchBody(): String {
        val source = readProjectFile("src/main/java/com/fskeyboard/ime/keyboard/KeyboardView.kt")
        val signature = source.indexOf("private fun dispatch(")
        assertTrue(signature >= 0)
        val open = source.indexOf('{', signature)
        assertTrue(open > signature)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripComments(source.substring(open + 1, i))
                }
            }
        }
        error("unclosed dispatch")
    }

    private fun stripComments(source: String): String {
        val withoutBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(source, "")
        return withoutBlock.lineSequence().joinToString("\n") { line ->
            val mark = line.indexOf("//")
            if (mark < 0) line else line.substring(0, mark)
        }
    }

    private data class Call(val at: Int, val argument: String)

    private fun calls(body: String, name: String): List<Call> {
        val found = ArrayList<Call>()
        var from = 0
        while (from < body.length) {
            val at = body.indexOf("$name(", from)
            if (at < 0) break
            val preceded = at > 0 && (body[at - 1].isLetterOrDigit() || body[at - 1] == '_')
            if (preceded) {
                from = at + name.length
                continue
            }
            var depth = 0
            var i = at + name.length
            var closed = false
            while (i < body.length) {
                when (body[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            found += Call(at, body.substring(at + name.length + 1, i))
                            from = i + 1
                            closed = true
                            break
                        }
                    }
                }
                i++
            }
            if (!closed) break
        }
        return found
    }
}
