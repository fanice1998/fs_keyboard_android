package com.fskeyboard.ime.state

import com.fskeyboard.ime.compose.EnglishCompositionSession
import com.fskeyboard.ime.keyboard.KeyAction

/**
 * Symbol pages share [english] with Latin. [allowCompose] is true when the page returns to Latin,
 * so a letter can start a word and '-' can continue one. A non-continuing character finishes it.
 */
internal class SymbolStateController(
    private val english: EnglishCompositionSession,
    private val allowCompose: () -> Boolean,
) : KeyState {
    override fun handle(action: KeyAction): KeyFate {
        if (action !is KeyAction.Character && action !is KeyAction.Space &&
            action !is KeyAction.Backspace && action != KeyAction.Enter
        ) {
            return KeyFate.Continue
        }
        return english.handle(action, allowCompose())
    }
}
