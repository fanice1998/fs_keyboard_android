package com.fskeyboard.ime.keyboard

/**
 * The floating label shown above a pressed letter, zhuyin, or symbol.
 * Space, delete, and the other function keys stay quiet, the way iOS and Samsung do.
 * [LINGER_MS] keeps a quick tap visible after the finger lifts.
 */
internal object KeyPreview {
    const val LINGER_MS = 120L

    fun visible(spec: KeySpec): Boolean {
        if (spec.icon != null || spec.label.isEmpty()) return false
        return spec.role == KeyRole.NORMAL || spec.role == KeyRole.TONE
    }

    fun textSizeSp(label: String): Float = when {
        label.length >= 4 -> 16f
        label.length > 1 -> 22f
        else -> 32f
    }

    /**
     * Centers the bubble on the key, then shifts it so it stays inside the keyboard.
     * [PreviewFrame.top] is negative when the bubble floats above the top row.
     */
    fun place(
        keyLeft: Float,
        keyTop: Float,
        keyRight: Float,
        boardWidth: Float,
        popupWidth: Float,
        popupHeight: Float,
        margin: Float,
        gap: Float,
    ): PreviewFrame {
        val available = (boardWidth - 2f * margin).coerceAtLeast(0f)
        val width = popupWidth.coerceAtMost(available).coerceAtLeast(0f)
        val minLeft = margin
        val maxLeft = (boardWidth - margin - width).coerceAtLeast(minLeft)
        val center = (keyLeft + keyRight) / 2f
        val left = (center - width / 2f).coerceIn(minLeft, maxLeft)
        return PreviewFrame(
            left = left,
            top = keyTop - gap - popupHeight,
            width = width,
            height = popupHeight,
        )
    }
}

internal data class PreviewFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)
