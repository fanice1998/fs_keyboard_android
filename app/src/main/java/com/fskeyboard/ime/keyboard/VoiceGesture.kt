package com.fskeyboard.ime.keyboard

/**
 * Voice input is a space tap followed quickly by a still hold.
 * A plain long-press, a late second press, or a slide stays a cursor glide.
 */
internal object VoiceGesture {
    const val NONE = -1L
    const val TAP_WINDOW_MS = 550L
    const val HOLD_MS = 400L

    /** True when [downAt] is a space press within [TAP_WINDOW_MS] of the previous tap. */
    fun armed(previousTapAt: Long, downAt: Long): Boolean {
        if (previousTapAt == NONE || downAt < previousTapAt) return false
        return downAt - previousTapAt <= TAP_WINDOW_MS
    }
}
