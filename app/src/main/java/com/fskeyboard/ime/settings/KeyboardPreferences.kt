package com.fskeyboard.ime.settings

import android.content.Context
import com.fskeyboard.ime.keyboard.KeyboardHeight
import com.fskeyboard.ime.rerank.RerankPreferences

/**
 * Keyboard height stored in the app's private preferences.
 * The value survives process death and reboot, and is cleared only when the app data is removed.
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

    companion object {
        const val KEY_ROW_DP = "keyboard_row_height_dp"
    }
}
