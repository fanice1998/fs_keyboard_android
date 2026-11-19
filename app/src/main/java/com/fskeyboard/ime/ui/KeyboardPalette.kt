package com.fskeyboard.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View

internal data class KeyboardPalette(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val keyAction: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    /** Raised card for the floating key label. */
    val keyPreview: Int,
) {
    companion object {
        fun of(night: Boolean): KeyboardPalette = if (night) DARK else LIGHT

        private val LIGHT = KeyboardPalette(
            background = 0xFFE6E8EB.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFC5D8F2.toInt(),
            keyAction = 0xFFD0D4DA.toInt(),
            text = 0xFF1C1C1E.toInt(),
            hint = 0xFF6B7280.toInt(),
            accent = 0xFFD6E6FA.toInt(),
            keyPreview = 0xFFFFFFFF.toInt(),
        )

        private val DARK = KeyboardPalette(
            background = 0xFF1C1C1E.toInt(),
            key = 0xFF2C2C2E.toInt(),
            keyPressed = 0xFF3A4A63.toInt(),
            keyAction = 0xFF3A3A3C.toInt(),
            text = 0xFFF2F2F7.toInt(),
            hint = 0xFFA1A1AA.toInt(),
            accent = 0xFF31445F.toInt(),
            keyPreview = 0xFF636366.toInt(),
        )
    }
}

internal fun Context.isNightMode(): Boolean {
    val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return mask == Configuration.UI_MODE_NIGHT_YES
}

internal fun View.isNightMode(): Boolean = context.isNightMode()

internal fun View.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

internal fun android.content.Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/**
 * The prediction chip sits one elevation step away from a normal candidate.
 * A dark key goes lighter. A light key that is already near white sinks below the bar.
 */
internal fun KeyboardPalette.predictionFill(): Int {
    fun channel(color: Int, shift: Int) = (color ushr shift) and 0xFF
    fun moved(shift: Int): Int {
        val keyChannel = channel(key, shift)
        val barChannel = channel(background, shift)
        val step = keyChannel - barChannel
        val raised = keyChannel + step
        return if (raised in 0..255) raised else (barChannel - step).coerceIn(0, 255)
    }
    return (0xFF shl 24) or (moved(16) shl 16) or (moved(8) shl 8) or moved(0)
}

