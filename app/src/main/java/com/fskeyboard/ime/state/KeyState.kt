package com.fskeyboard.ime.state

import com.fskeyboard.ime.keyboard.KeyAction

internal enum class KeyFate {
    /** This key's text is already written. The router must not commit it again. */
    Consumed,

    /**
     * This state did not take the key: no finish, no commit, no insert.
     * The router then does the default. Finishing a composition and then
     * returning [Continue] would commit the same key twice.
     */
    Continue,
}

/** One keyboard's key consumption. It does not see the mode, and it cannot send key events. */
internal interface KeyState {
    fun handle(action: KeyAction): KeyFate
}
