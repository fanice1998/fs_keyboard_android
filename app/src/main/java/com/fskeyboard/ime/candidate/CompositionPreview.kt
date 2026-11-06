package com.fskeyboard.ime.candidate

/**
 * Where the Samsung-style reading bubble sits: on the top-left corner of the
 * candidate bar, hanging up into the app. One string, not a block per symbol.
 * [FILL] is the translucent dark gray the reading is drawn on, with [TEXT] white.
 */
internal object CompositionPreview {
    const val FILL: Int = 0xB32C2C2E.toInt()
    const val TEXT: Int = 0xFFFFFFFF.toInt()

    /**
     * [anchorTop] is the candidate bar's top in the keyboard window.
     * The bubble's bottom edge meets that line, so [CompositionPreviewFrame.top]
     * is above the keyboard. A long reading is narrowed to stay inside
     * [boundsLeft, boundsRight].
     */
    fun place(
        anchorLeft: Int,
        anchorTop: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        boundsLeft: Int,
        boundsRight: Int,
        margin: Int,
    ): CompositionPreviewFrame {
        val available = (boundsRight - boundsLeft - 2 * margin).coerceAtLeast(0)
        val width = bubbleWidth.coerceIn(0, available)
        val minLeft = boundsLeft + margin
        val maxLeft = (boundsRight - margin - width).coerceAtLeast(minLeft)
        val left = (anchorLeft + margin).coerceIn(minLeft, maxLeft)
        return CompositionPreviewFrame(
            left = left,
            top = anchorTop - bubbleHeight,
            width = width,
            height = bubbleHeight.coerceAtLeast(0),
        )
    }
}

internal data class CompositionPreviewFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)
