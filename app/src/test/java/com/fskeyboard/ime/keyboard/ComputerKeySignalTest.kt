package com.fskeyboard.ime.keyboard

import android.text.InputType
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerKeySignalTest {
    @Test
    fun signalKeyMapsEditingKeysAndIgnoresModifiersAndSymbols() {
        assertEquals(SignalKey.FORWARD_DELETE, signalKey(ComputerKey.FORWARD_DELETE))
        assertEquals(SignalKey.TAB, signalKey(ComputerKey.TAB))
        assertEquals(SignalKey.ESCAPE, signalKey(ComputerKey.ESC))
        assertEquals(SignalKey.UP, signalKey(ComputerKey.UP))
        assertEquals(SignalKey.DOWN, signalKey(ComputerKey.DOWN))
        assertEquals(SignalKey.LEFT, signalKey(ComputerKey.LEFT))
        assertEquals(SignalKey.RIGHT, signalKey(ComputerKey.RIGHT))
        assertEquals(SignalKey.HOME, signalKey(ComputerKey.HOME))
        assertEquals(SignalKey.END, signalKey(ComputerKey.END))
        assertEquals(SignalKey.PAGE_UP, signalKey(ComputerKey.PAGE_UP))
        assertEquals(SignalKey.PAGE_DOWN, signalKey(ComputerKey.PAGE_DOWN))
        assertNull(signalKey(ComputerKey.CTRL))
        assertNull(signalKey(ComputerKey.BANG))
        assertNull(signalKey(ComputerKey.GRAVE))
        val mapped = setOf(
            ComputerKey.FORWARD_DELETE,
            ComputerKey.TAB,
            ComputerKey.ESC,
            ComputerKey.UP,
            ComputerKey.DOWN,
            ComputerKey.LEFT,
            ComputerKey.RIGHT,
            ComputerKey.HOME,
            ComputerKey.END,
            ComputerKey.PAGE_UP,
            ComputerKey.PAGE_DOWN,
        )
        for (key in ComputerKey.values()) {
            if (key in mapped) continue
            assertNull(key.name, signalKey(key))
        }
    }

    @Test
    fun navigationIsTheKeyEventWithOnlyTheLatchedMeta() {
        val keys = listOf(
            SignalKey.ESCAPE to KeyEvent.KEYCODE_ESCAPE,
            SignalKey.UP to KeyEvent.KEYCODE_DPAD_UP,
            SignalKey.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            SignalKey.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            SignalKey.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            SignalKey.HOME to KeyEvent.KEYCODE_MOVE_HOME,
            SignalKey.END to KeyEvent.KEYCODE_MOVE_END,
            SignalKey.PAGE_UP to KeyEvent.KEYCODE_PAGE_UP,
            SignalKey.PAGE_DOWN to KeyEvent.KEYCODE_PAGE_DOWN,
        )
        val shiftBits = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        for (inputType in fields) {
            for (shift in ShiftMode.values()) {
                for (meta in metas) {
                    for ((signal, keyCode) in keys) {
                        val event = keySignal(signal, inputType, shift, meta)
                        assertEquals(
                            "$signal type=$inputType shift=$shift meta=$meta",
                            KeySignal.Event(keyCode, meta),
                            event,
                        )
                        assertEquals(0, (event as KeySignal.Event).meta and shiftBits and meta.inv())
                    }
                }
            }
        }
    }

    @Test
    fun tabAddsShiftBitsOnlyWhenShiftIsNotOff() {
        val shiftBits = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        for (inputType in fields) {
            for (meta in metas) {
                assertEquals(
                    "tab off type=$inputType meta=$meta",
                    KeySignal.Event(KeyEvent.KEYCODE_TAB, meta),
                    keySignal(SignalKey.TAB, inputType, ShiftMode.OFF, meta),
                )
                for (shift in listOf(ShiftMode.ONCE, ShiftMode.LOCKED)) {
                    val event = keySignal(SignalKey.TAB, inputType, shift, meta) as KeySignal.Event
                    assertEquals(KeyEvent.KEYCODE_TAB, event.keyCode)
                    assertEquals(shiftBits, event.meta and shiftBits)
                    assertEquals(meta, event.meta and shiftBits.inv())
                }
            }
        }
    }

    @Test
    fun backspaceAndForwardDeleteEditAReadableFieldWhenNothingIsLatched() {
        for (inputType in readableFields) {
            for (shift in ShiftMode.values()) {
                assertEquals(
                    "backspace type=$inputType shift=$shift",
                    KeySignal.Surrounding(before = 1, after = 0, fallbackKey = KeyEvent.KEYCODE_DEL),
                    keySignal(SignalKey.BACKSPACE, inputType, shift, 0),
                )
                assertEquals(
                    "forward delete type=$inputType shift=$shift",
                    KeySignal.Surrounding(before = 0, after = 1, fallbackKey = KeyEvent.KEYCODE_FORWARD_DEL),
                    keySignal(SignalKey.FORWARD_DELETE, inputType, shift, 0),
                )
            }
        }
    }

    @Test
    fun aNullFieldSendsDeleteWithNoMetaWhenNothingIsLatched() {
        for (shift in ShiftMode.values()) {
            assertEquals(
                KeySignal.Event(KeyEvent.KEYCODE_DEL, 0),
                keySignal(SignalKey.BACKSPACE, InputType.TYPE_NULL, shift, 0),
            )
            assertEquals(
                KeySignal.Event(KeyEvent.KEYCODE_FORWARD_DEL, 0),
                keySignal(SignalKey.FORWARD_DELETE, InputType.TYPE_NULL, shift, 0),
            )
        }
    }

    @Test
    fun aLatchedModifierSendsDeleteOnEveryFieldIncludingNullAndPassword() {
        for (inputType in fields) {
            for (meta in metas) {
                if (meta == 0) continue
                for (shift in ShiftMode.values()) {
                    assertEquals(
                        "backspace type=$inputType shift=$shift meta=$meta",
                        KeySignal.Event(KeyEvent.KEYCODE_DEL, meta),
                        keySignal(SignalKey.BACKSPACE, inputType, shift, meta),
                    )
                    assertEquals(
                        "forward delete type=$inputType shift=$shift meta=$meta",
                        KeySignal.Event(KeyEvent.KEYCODE_FORWARD_DEL, meta),
                        keySignal(SignalKey.FORWARD_DELETE, inputType, shift, meta),
                    )
                }
            }
        }
    }

    private val readableFields = listOf(
        InputType.TYPE_CLASS_TEXT,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME,
    )

    private val fields = readableFields + InputType.TYPE_NULL

    private val metas = listOf(
        0,
        KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
        KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
        KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
        KeyEvent.META_FUNCTION_ON,
    )
}
