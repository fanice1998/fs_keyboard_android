package com.fskeyboard.ime.settings

import android.content.Context
import com.fskeyboard.ime.keyboard.KeyboardHeight
import com.fskeyboard.ime.rerank.RerankPreferences

/**
 * Keyboard height and symbol width stored in the app's private preferences.
 * The values survive process death and reboot, and are cleared only when the app data is removed.
 */
internal class KeyboardPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        RerankPreferences.NAME,
        Context.MODE_PRIVATE,
    )

    fun rowHeightDp(): Int = KeyboardHeight.clamp(
        prefs.getInt(KEY_ROW_DP, KeyboardHeight.DEFAULT_ROW_DP),
    )

    fun setRowHeightDp(rowDp: Int, flush: Boolean = false) {
        val editor = prefs.edit().putInt(KEY_ROW_DP, KeyboardHeight.clamp(rowDp))
        if (flush) editor.commit() else editor.apply()
    }

    /** Whether the symbol pages opened from zhuyin use full-width Chinese punctuation. */
    fun fullWidthSymbols(): Boolean = prefs.getBoolean(KEY_FULL_WIDTH, true)

    fun setFullWidthSymbols(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FULL_WIDTH, enabled).apply()
    }

    companion object {
        const val KEY_ROW_DP = "keyboard_row_height_dp"
        const val KEY_FULL_WIDTH = "zhuyin_full_width_symbols"
    }
}
