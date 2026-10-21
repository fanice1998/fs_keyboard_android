package com.fskeyboard.ime.ui

import android.content.res.Configuration
import android.graphics.Color
import android.view.View

internal data class KeyboardPalette(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val keyAction: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
) {
    companion object {
        fun of(night: Boolean): KeyboardPalette = if (night) DARK else LIGHT

        private val LIGHT = KeyboardPalette(
            background = Color.parseColor("#E6E8EB"),
            key = Color.parseColor("#FFFFFF"),
            keyPressed = Color.parseColor("#C5D8F2"),
            keyAction = Color.parseColor("#D0D4DA"),
            text = Color.parseColor("#1C1C1E"),
            hint = Color.parseColor("#6B7280"),
            accent = Color.parseColor("#D6E6FA"),
        )

        private val DARK = KeyboardPalette(
            background = Color.parseColor("#1C1C1E"),
            key = Color.parseColor("#2C2C2E"),
            keyPressed = Color.parseColor("#3A4A63"),
            keyAction = Color.parseColor("#3A3A3C"),
            text = Color.parseColor("#F2F2F7"),
            hint = Color.parseColor("#A1A1AA"),
            accent = Color.parseColor("#31445F"),
        )
    }
}

internal fun View.isNightMode(): Boolean {
    val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return mask == Configuration.UI_MODE_NIGHT_YES
}

internal fun View.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

internal fun android.content.Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

