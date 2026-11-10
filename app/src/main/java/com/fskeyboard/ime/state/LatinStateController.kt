package com.fskeyboard.ime.state

import com.fskeyboard.ime.candidate.EnglishInputPolicy
import com.fskeyboard.ime.compose.EnglishCompositionSession
import com.fskeyboard.ime.compose.EnglishHost
import com.fskeyboard.ime.keyboard.KeyAction

/**
 * Latin letters, space, backspace, and enter go through [english].
 * `@` shows domains after the word is finished or the character is committed, not before.
 */
internal class LatinStateController(
    private val english: EnglishCompositionSession,
    private val host: EnglishHost,
) : KeyState {
    override fun handle(action: KeyAction): KeyFate {
        if (action !is KeyAction.Character && action !is KeyAction.Space &&
            action !is KeyAction.Backspace && action != KeyAction.Enter
        ) {
            return KeyFate.Continue
        }
        val fate = english.handle(
            action,
            allowCompose = EnglishInputPolicy.shouldCompose(host.editorInfo()),
        )
        if (action is KeyAction.Character) {
            if (fate == KeyFate.Continue) host.commit(action.value.toString())
            if (action.value == '@' && !english.lastCharacterInserted && host.isEmailContext()) {
                host.showEmailDomainsAfterAt()
            }
            return KeyFate.Consumed
        }
        return fate
    }
}
