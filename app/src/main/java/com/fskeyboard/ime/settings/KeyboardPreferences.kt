package com.fskeyboard.ime.settings

import android.content.Context
import com.fskeyboard.ime.keyboard.ComputerKeyMap
import com.fskeyboard.ime.keyboard.KeyboardHeight
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.rerank.RerankPreferences

/**
 * Keyboard height, symbol width, key feedback, and the last letter keyboard.
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

    /** Whether the Latin keyboard top row has secondary number keys. */
    fun latinTopNumberRow(): Boolean = prefs.getBoolean(KEY_LATIN_TOP_NUMBER_ROW, true)

    fun setLatinTopNumberRow(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LATIN_TOP_NUMBER_ROW, enabled).apply()
    }

    /** Whether a swipe on an English letter sends the computer key assigned to that direction. */
    fun computerKeys(): Boolean = prefs.getBoolean(KEY_COMPUTER_KEYS, false)

    fun setComputerKeys(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPUTER_KEYS, enabled).apply()
    }

    /**
     * Swipe assignments for the English computer keys.
     * A missing preference keeps the built-in upward map. An empty string is a map the user cleared.
     */
    fun computerKeyMap(): ComputerKeyMap {
        if (!prefs.contains(KEY_COMPUTER_KEY_MAP)) return ComputerKeyMap.DEFAULT
        return ComputerKeyMap.decode(prefs.getString(KEY_COMPUTER_KEY_MAP, "").orEmpty())
    }

    fun setComputerKeyMap(map: ComputerKeyMap) {
        prefs.edit().putString(KEY_COMPUTER_KEY_MAP, map.encode()).apply()
    }

    /** Whether key presses produce haptic vibration feedback. */
    fun vibrationEnabled(): Boolean = prefs.getBoolean(KEY_VIBRATION, true)

    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }

    /** Whether a press floats the key's label above the key. On unless the user turns it off. */
    fun keyPreviewEnabled(): Boolean = prefs.getBoolean(KEY_PREVIEW, true)

    fun setKeyPreviewEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW, enabled).apply()
    }

    /**
     * The letter keyboard last chosen by the user. Missing or unknown values are zhuyin.
     * Symbol pages are not stored. An email field must not call [setLetterMode].
     */
    fun letterMode(): KeyboardMode {
        return when (prefs.getString(KEY_LETTER_MODE, null)) {
            "latin" -> KeyboardMode.LATIN
            else -> KeyboardMode.ZHUYIN
        }
    }

    fun setLetterMode(mode: KeyboardMode) {
        if (!mode.isLetters) return
        val name = if (mode == KeyboardMode.LATIN) "latin" else "zhuyin"
        if (prefs.getString(KEY_LETTER_MODE, null) == name) return
        prefs.edit().putString(KEY_LETTER_MODE, name).apply()
    }

    companion object {
        const val KEY_ROW_DP = "keyboard_row_height_dp"
        const val KEY_FULL_WIDTH = "zhuyin_full_width_symbols"
        const val KEY_LATIN_TOP_NUMBER_ROW = "latin_top_number_row"
        const val KEY_COMPUTER_KEYS = "latin_computer_keys"
        const val KEY_COMPUTER_KEY_MAP = "latin_computer_key_map"
        const val KEY_VIBRATION = "keyboard_vibration_enabled"
        const val KEY_PREVIEW = "key_preview_enabled"
        const val KEY_LETTER_MODE = "letter_mode"
    }
}
