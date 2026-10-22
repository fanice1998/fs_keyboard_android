package com.fskeyboard.ime.keyboard

import android.view.KeyEvent
import kotlin.math.abs

/**
 * Computer keys reached by swiping an English letter upward.
 * [modifier] keys wait for the next key instead of sending themselves.
 */
internal enum class ComputerKey(
    val label: String,
    val keyCode: Int,
    val modifier: Boolean = false,
) {
    ESC("Esc", KeyEvent.KEYCODE_ESCAPE),
    TAB("Tab", KeyEvent.KEYCODE_TAB),
    CTRL("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, modifier = true),
    ALT("Alt", KeyEvent.KEYCODE_ALT_LEFT, modifier = true),
    UP("↑", KeyEvent.KEYCODE_DPAD_UP),
    DOWN("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("←", KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    HOME("Home", KeyEvent.KEYCODE_MOVE_HOME),
    END("End", KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
    FORWARD_DELETE("Del", KeyEvent.KEYCODE_FORWARD_DEL),
    META("Meta", KeyEvent.KEYCODE_META_LEFT, modifier = true),
    ;

    val metaState: Int
        get() = when (this) {
            CTRL -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            ALT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            META -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            else -> 0
        }
}

/** Key code for a typed Latin letter or digit. Punctuation has none. */
internal fun computerKeyCode(value: Char): Int? {
    val lower = value.lowercaseChar()
    if (lower in 'a'..'z') return KeyEvent.KEYCODE_A + (lower - 'a')
    if (value in '0'..'9') return KeyEvent.KEYCODE_0 + (value - '0')
    return null
}

/**
 * Ctrl, Alt, and Meta selected by an upward swipe.
 * Tapping the same one again removes it. A following key consumes the set.
 */
internal class ComputerModifiers {
    private val armed = linkedSetOf<ComputerKey>()

    fun active(): Set<ComputerKey> = armed.toSet()

    fun isActive(): Boolean = armed.isNotEmpty()

    fun toggle(key: ComputerKey) {
        if (!key.modifier) return
        if (!armed.add(key)) armed.remove(key)
    }

    fun clear() {
        armed.clear()
    }

    fun metaState(shift: Boolean = false): Int {
        var meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        for (key in armed) meta = meta or key.metaState
        return meta
    }
}

/**
 * Upward travel from the key's touch-down point.
 * Movement inside [slop] stays a tap. A swipe counts when it is upward and at least as vertical as it is horizontal.
 */
internal class KeySwipe(private val slop: Float) {
    var armed: Boolean = false
        private set

    fun move(dx: Float, dy: Float): Boolean {
        if (dx * dx + dy * dy < slop * slop) {
            armed = false
            return false
        }
        armed = dy < 0f && -dy >= abs(dx)
        return armed
    }
}
