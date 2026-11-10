package com.fskeyboard.ime.keyboard

import android.text.InputType
import android.view.KeyEvent

internal sealed interface KeySignal {
    data class Event(val keyCode: Int, val meta: Int) : KeySignal

    /** Text edit. fallbackKey is sent later only when a readable field's text does not change. */
    data class Surrounding(val before: Int, val after: Int, val fallbackKey: Int) : KeySignal
}

internal enum class SignalKey {
    BACKSPACE,
    FORWARD_DELETE,
    TAB,
    ESCAPE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
}

internal fun signalKey(key: ComputerKey): SignalKey? = when (key) {
    ComputerKey.FORWARD_DELETE -> SignalKey.FORWARD_DELETE
    ComputerKey.TAB -> SignalKey.TAB
    ComputerKey.ESC -> SignalKey.ESCAPE
    ComputerKey.UP -> SignalKey.UP
    ComputerKey.DOWN -> SignalKey.DOWN
    ComputerKey.LEFT -> SignalKey.LEFT
    ComputerKey.RIGHT -> SignalKey.RIGHT
    ComputerKey.HOME -> SignalKey.HOME
    ComputerKey.END -> SignalKey.END
    ComputerKey.PAGE_UP -> SignalKey.PAGE_UP
    ComputerKey.PAGE_DOWN -> SignalKey.PAGE_DOWN
    else -> null
}

internal fun keySignal(key: SignalKey, inputType: Int, shift: ShiftMode, latchedMeta: Int): KeySignal =
    when (key) {
        SignalKey.ESCAPE -> KeySignal.Event(ComputerKey.ESC.keyCode, latchedMeta)
        SignalKey.UP -> KeySignal.Event(ComputerKey.UP.keyCode, latchedMeta)
        SignalKey.DOWN -> KeySignal.Event(ComputerKey.DOWN.keyCode, latchedMeta)
        SignalKey.LEFT -> KeySignal.Event(ComputerKey.LEFT.keyCode, latchedMeta)
        SignalKey.RIGHT -> KeySignal.Event(ComputerKey.RIGHT.keyCode, latchedMeta)
        SignalKey.HOME -> KeySignal.Event(ComputerKey.HOME.keyCode, latchedMeta)
        SignalKey.END -> KeySignal.Event(ComputerKey.END.keyCode, latchedMeta)
        SignalKey.PAGE_UP -> KeySignal.Event(ComputerKey.PAGE_UP.keyCode, latchedMeta)
        SignalKey.PAGE_DOWN -> KeySignal.Event(ComputerKey.PAGE_DOWN.keyCode, latchedMeta)
        SignalKey.TAB -> KeySignal.Event(KeyEvent.KEYCODE_TAB, tabMeta(shift, latchedMeta))
        SignalKey.BACKSPACE ->
            deleteSignal(KeyEvent.KEYCODE_DEL, before = 1, after = 0, inputType, latchedMeta)
        SignalKey.FORWARD_DELETE ->
            deleteSignal(KeyEvent.KEYCODE_FORWARD_DEL, before = 0, after = 1, inputType, latchedMeta)
    }

private fun tabMeta(shift: ShiftMode, latchedMeta: Int): Int {
    if (shift == ShiftMode.OFF) return latchedMeta
    return latchedMeta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
}

private fun deleteSignal(
    keyCode: Int,
    before: Int,
    after: Int,
    inputType: Int,
    latchedMeta: Int,
): KeySignal {
    // Password stays a text class. A latch or TYPE_NULL is the only delete that is a key event.
    if (latchedMeta != 0 || (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) {
        return KeySignal.Event(keyCode, latchedMeta)
    }
    return KeySignal.Surrounding(before = before, after = after, fallbackKey = keyCode)
}
