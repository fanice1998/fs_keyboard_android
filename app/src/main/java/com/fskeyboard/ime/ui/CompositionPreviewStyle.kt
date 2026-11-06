package com.fskeyboard.ime.ui

/**
 * Colors for the zhuyin preview above the candidate row.
 * One block is one Hanzi or one bopomofo symbol.
 * Swap this object later to theme the preview without touching the row.
 */
internal data class CompositionPreviewStyle(
    /** Translucent dark gray behind a block. */
    val block: Int,
    /** Glyph drawn on [block]. */
    val text: Int,
) {
    companion object {
        fun standard(): CompositionPreviewStyle = CompositionPreviewStyle(
            block = 0xB32C2C2E.toInt(),
            text = 0xFFFFFFFF.toInt(),
        )
    }
}
