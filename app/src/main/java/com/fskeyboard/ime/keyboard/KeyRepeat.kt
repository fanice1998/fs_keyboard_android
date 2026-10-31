package com.fskeyboard.ime.keyboard

/**
 * How long a finger has to stay down before a key starts repeating, and how fast it then repeats.
 * Backspace used to repeat every 45ms. [DELETE_MS] is a little faster.
 * An arrow, Home, End, or a modifier repeats only after the finger has held.
 * A quick tap or swipe still fires once.
 */
internal object KeyRepeat {
    const val DELAY_MS = 320L
    const val DELETE_MS = 32L
    const val ARROW_MS = 50L
    const val LETTER_MS = 50L

    fun interval(action: KeyAction): Long? = when {
        action == KeyAction.Backspace -> DELETE_MS
        action is KeyAction.Computer && action.key.repeats -> ARROW_MS
        action is KeyAction.Character && action.value.repeatsWhenHeld() -> LETTER_MS
        else -> null
    }

    /**
     * Letters repeat only on the English keyboard.
     * A symbol page uses the same character action and must not run on.
     */
    fun schedules(mode: KeyboardMode, action: KeyAction): Boolean {
        if (interval(action) == null) return false
        if (action is KeyAction.Character) return mode == KeyboardMode.LATIN
        return true
    }

    private fun Char.repeatsWhenHeld(): Boolean {
        return isLetter() || isDigit() || this == ',' || this == '.'
    }

    /**
     * The hold timer already delivered [action], so lifting the finger must not deliver it again.
     * [backspaceSent] covers the backspace that fires on touch-down, before the timer.
     */
    fun alreadyDelivered(action: KeyAction, repeating: KeyAction?, backspaceSent: Boolean): Boolean {
        if (action == KeyAction.Backspace && backspaceSent) return true
        if (repeating == null || interval(action) == null) return false
        if (repeating == action) return true
        // A held modifier is dispatched with repeating set, and released as the plain key.
        val fired = repeating
        return action is KeyAction.Computer &&
            fired is KeyAction.Computer &&
            action.key == fired.key
    }
}
