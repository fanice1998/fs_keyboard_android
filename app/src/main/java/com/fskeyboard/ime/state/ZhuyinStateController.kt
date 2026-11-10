package com.fskeyboard.ime.state

import com.fskeyboard.ime.compose.ZhuyinCompositionSession
import com.fskeyboard.ime.keyboard.KeyAction

/** Bopomofo, space, backspace, and enter for the zhuyin session. Punctuation stays on the router. */
internal class ZhuyinStateController(
    private val session: ZhuyinCompositionSession,
) : KeyState {
    override fun handle(action: KeyAction): KeyFate = when (action) {
        is KeyAction.Bopomofo -> if (session.onKey(action)) KeyFate.Consumed else KeyFate.Continue
        KeyAction.Space -> if (session.onSpace()) KeyFate.Consumed else KeyFate.Continue
        KeyAction.Backspace -> if (session.onBackspace()) KeyFate.Consumed else KeyFate.Continue
        KeyAction.Enter -> if (session.onEnter()) KeyFate.Consumed else KeyFate.Continue
        else -> KeyFate.Continue
    }
}
