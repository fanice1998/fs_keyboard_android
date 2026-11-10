package com.fskeyboard.ime.state

import com.fskeyboard.ime.keyboard.KeyAction

/** Digit-pad characters commit as themselves. They do not join an English word or learn a symbol. */
internal class NumberStateController(
    private val commit: (String) -> Unit,
) : KeyState {
    override fun handle(action: KeyAction): KeyFate {
        if (action is KeyAction.Character) {
            commit(action.value.toString())
            return KeyFate.Consumed
        }
        return KeyFate.Continue
    }
}
