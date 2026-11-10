package com.fskeyboard.ime.state

import com.fskeyboard.ime.keyboard.KeyboardMode

internal enum class CompositionLeave { KEEP, COMMIT_TYPED }

internal data class LeaveDecision(
    val english: CompositionLeave,
    val zhuyin: CompositionLeave,
)

/**
 * What [com.fskeyboard.ime.candidate.CandidateController.setMode] applies after it has
 * already rejected [KeyboardMode.NUMBER] and a switch to the same mode.
 * Those inputs are still defined, and both sides stay [CompositionLeave.KEEP], so a caller
 * that forgets the early return does not commit. There is no drop.
 */
internal fun modeLeave(from: KeyboardMode, to: KeyboardMode): LeaveDecision {
    if (from == KeyboardMode.NUMBER || to == KeyboardMode.NUMBER || from == to) {
        return LeaveDecision(CompositionLeave.KEEP, CompositionLeave.KEEP)
    }
    val betweenLatinAndSymbols = (from == KeyboardMode.LATIN && to.isSymbols) ||
        (from.isSymbols && to == KeyboardMode.LATIN)
    val english = if (betweenLatinAndSymbols) {
        CompositionLeave.KEEP
    } else {
        CompositionLeave.COMMIT_TYPED
    }
    val zhuyin = if (from == KeyboardMode.ZHUYIN) {
        CompositionLeave.COMMIT_TYPED
    } else {
        CompositionLeave.KEEP
    }
    return LeaveDecision(english, zhuyin)
}
