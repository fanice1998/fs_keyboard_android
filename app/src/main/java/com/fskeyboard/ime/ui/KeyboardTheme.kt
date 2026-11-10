package com.fskeyboard.ime.ui

/**
 * One keyboard appearance. The composer and the lexicon do not read this.
 * [pressMs] is how long a key preview stays after the finger lifts. 0 uses the
 * default linger. [imagePath] is reserved for a future bitmap; empty draws the palette.
 */
internal data class KeyboardTheme(
    val id: String,
    val light: KeyboardPalette,
    val dark: KeyboardPalette,
    val cornerDp: Int,
    val borderDp: Int,
    val pressMs: Int,
    val imagePath: String = "",
) {
    fun palette(night: Boolean): KeyboardPalette = if (night) dark else light

    companion object {
        const val PAPER = "paper"
        const val INK = "ink"

        fun of(id: String?): KeyboardTheme = if (id == INK) ink() else paper()

        fun paper(): KeyboardTheme = PAPER_THEME

        fun ink(): KeyboardTheme = INK_THEME

        private val PAPER_THEME = KeyboardTheme(
            id = PAPER,
            light = palette(0xFFE6E8EB, 0xFFFFFFFF, 0xFFC5D8F2, 0xFFD0D4DA, 0xFF1C1C1E, 0xFF6B7280, 0xFFD6E6FA, 0xFFFFFFFF),
            dark = palette(0xFF1C1C1E, 0xFF2C2C2E, 0xFF3A4A63, 0xFF3A3A3C, 0xFFF2F2F7, 0xFFA1A1AA, 0xFF31445F, 0xFF636366),
            cornerDp = 11,
            borderDp = 0,
            pressMs = 0,
        )

        private val INK_THEME = KeyboardTheme(
            id = INK,
            light = palette(0xFFD9D4CC, 0xFFF7F4EF, 0xFFC9BBA6, 0xFFE4DDD4, 0xFF1A1814, 0xFF6F675C, 0xFFE7D3B0, 0xFFF7F4EF),
            dark = palette(0xFF12110F, 0xFF242220, 0xFF4A4034, 0xFF3A342C, 0xFFF4EFE6, 0xFFB7A99A, 0xFF5C4A32, 0xFF3A342C),
            cornerDp = 8,
            borderDp = 2,
            pressMs = 160,
        )

        private fun palette(
            background: Long,
            key: Long,
            keyPressed: Long,
            keyAction: Long,
            text: Long,
            hint: Long,
            accent: Long,
            keyPreview: Long,
        ): KeyboardPalette = KeyboardPalette(
            background = background.toInt(),
            key = key.toInt(),
            keyPressed = keyPressed.toInt(),
            keyAction = keyAction.toInt(),
            text = text.toInt(),
            hint = hint.toInt(),
            accent = accent.toInt(),
            keyPreview = keyPreview.toInt(),
        )
    }
}
