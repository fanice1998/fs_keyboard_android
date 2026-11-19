package com.fskeyboard.ime.settings

import android.content.Context
import com.fskeyboard.ime.keyboard.ComputerKeyMap
import com.fskeyboard.ime.keyboard.KeyboardHeight
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.ui.AppearanceOverrides
import com.fskeyboard.ime.ui.KeyAnimation
import com.fskeyboard.ime.ui.acceptedImageName
import com.fskeyboard.ime.ui.readStoredAlpha
import com.fskeyboard.ime.ui.KeyboardTheme
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

    /**
     * Whether zhuyin uses the ten-column computer arrangement.
     * Off keeps the staggered zhuyin rows. Latin does not read this.
     */
    fun zhuyinComputerLayout(): Boolean = prefs.getBoolean(KEY_ZHUYIN_COMPUTER_LAYOUT, false)

    fun setZhuyinComputerLayout(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ZHUYIN_COMPUTER_LAYOUT, enabled).apply()
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
        return ComputerKeyMap.ensuringGrave(
            ComputerKeyMap.decode(prefs.getString(KEY_COMPUTER_KEY_MAP, "").orEmpty()),
        )
    }

    fun setComputerKeyMap(map: ComputerKeyMap) {
        prefs.edit().putString(KEY_COMPUTER_KEY_MAP, map.encode()).apply()
    }

    /** Whether key presses produce haptic vibration feedback. */
    fun vibrationEnabled(): Boolean = prefs.getBoolean(KEY_VIBRATION, true)

    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }

    /** Key-tap strength from 0 to 100. 60 is the lighter click used before the slider. */
    fun vibrationScale(): Int = prefs.getInt(KEY_VIBRATION_SCALE, VIBRATION_SCALE_DEFAULT)
        .coerceIn(0, VIBRATION_SCALE_MAX)

    fun setVibrationScale(percent: Int, flush: Boolean = false) {
        val editor = prefs.edit().putInt(
            KEY_VIBRATION_SCALE,
            percent.coerceIn(0, VIBRATION_SCALE_MAX),
        )
        if (flush) editor.commit() else editor.apply()
    }

    /** Whether a press floats the key's label above the key. On unless the user turns it off. */
    fun keyPreviewEnabled(): Boolean = prefs.getBoolean(KEY_PREVIEW, true)

    fun setKeyPreviewEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW, enabled).apply()
    }

    /** Paper unless the stored id is the ink theme. */
    fun keyboardTheme(): KeyboardTheme = KeyboardTheme.of(prefs.getString(KEY_THEME, null))

    fun setKeyboardTheme(theme: KeyboardTheme) {
        if (prefs.getString(KEY_THEME, null) == theme.id) return
        prefs.edit().putString(KEY_THEME, theme.id).apply()
    }

    /** Absent color keys stay null. 0 is a real color, so this cannot use getInt's default. */
    fun appearance(): AppearanceOverrides {
        return AppearanceOverrides(
            backgroundColor = colorOrNull(KEY_BACKGROUND_COLOR),
            keyFill = colorOrNull(KEY_KEY_FILL_COLOR),
            keyBorderEnabled = if (prefs.contains(KEY_KEY_BORDER_ENABLED)) {
                prefs.getBoolean(KEY_KEY_BORDER_ENABLED, false)
            } else {
                null
            },
            keyBorderColor = colorOrNull(KEY_KEY_BORDER_COLOR),
            keyLabelColor = colorOrNull(KEY_KEY_LABEL_COLOR),
            alphaPercent = readStoredAlpha(prefs.contains(KEY_ALPHA), prefs.getInt(KEY_ALPHA, 0)),
            animation = if (prefs.contains(KEY_ANIMATION)) {
                KeyAnimation.of(prefs.getString(KEY_ANIMATION, null))
            } else {
                null
            },
            imageName = acceptedImageName(
                if (prefs.contains(KEY_BACKGROUND_IMAGE)) prefs.getString(KEY_BACKGROUND_IMAGE, null) else null,
            ),
            candidateBar = colorOrNull(KEY_CANDIDATE_BAR),
            candidateSelected = colorOrNull(KEY_CANDIDATE_SELECTED),
            candidateUnselected = colorOrNull(KEY_CANDIDATE_UNSELECTED),
            candidateText = colorOrNull(KEY_CANDIDATE_TEXT),
        )
    }

    fun setAppearanceColor(key: String, argb: Int, flush: Boolean = false) {
        if (key !in APPEARANCE_COLOR_KEYS) return
        val editor = prefs.edit().putInt(key, argb or 0xFF000000.toInt())
        if (flush) editor.commit() else editor.apply()
    }

    fun clearAppearance(keys: Collection<String>, flush: Boolean = false) {
        val editor = prefs.edit()
        for (key in keys) {
            if (key !in APPEARANCE_KEYS) continue
            editor.remove(key)
        }
        if (flush) editor.commit() else editor.apply()
    }

    fun setKeyAnimation(animation: KeyAnimation, flush: Boolean = false) {
        val editor = prefs.edit().putString(KEY_ANIMATION, animation.stored())
        if (flush) editor.commit() else editor.apply()
    }

    fun setKeyboardAlpha(percent: Int, flush: Boolean = false) {
        val editor = prefs.edit().putInt(KEY_ALPHA, percent.coerceIn(0, 100))
        if (flush) editor.commit() else editor.apply()
    }

    fun setKeyBorderEnabled(enabled: Boolean, flush: Boolean = false) {
        val editor = prefs.edit().putBoolean(KEY_KEY_BORDER_ENABLED, enabled)
        if (flush) editor.commit() else editor.apply()
    }

    fun setBackgroundImageName(name: String?, flush: Boolean = false) {
        val editor = prefs.edit()
        if (name == null) editor.remove(KEY_BACKGROUND_IMAGE) else editor.putString(KEY_BACKGROUND_IMAGE, name)
        if (flush) editor.commit() else editor.apply()
    }

    private fun colorOrNull(key: String): Int? {
        if (!prefs.contains(key)) return null
        return prefs.getInt(key, 0) or 0xFF000000.toInt()
    }

    /**
     * Whether a remembered word that has been offered twenty times without being
     * chosen is dropped. On unless the user turns it off.
     */
    fun forgetUnusedPredictions(): Boolean = prefs.getBoolean(KEY_FORGET_UNUSED, true)

    fun setForgetUnusedPredictions(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORGET_UNUSED, enabled).apply()
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
        const val KEY_ZHUYIN_COMPUTER_LAYOUT = "zhuyin_computer_layout"
        const val KEY_LATIN_TOP_NUMBER_ROW = "latin_top_number_row"
        const val KEY_COMPUTER_KEYS = "latin_computer_keys"
        const val KEY_COMPUTER_KEY_MAP = "latin_computer_key_map"
        const val KEY_VIBRATION = "keyboard_vibration_enabled"
        const val KEY_VIBRATION_SCALE = "keyboard_vibration_scale"
        const val VIBRATION_SCALE_MAX = 100
        const val VIBRATION_SCALE_DEFAULT = 60
        const val KEY_PREVIEW = "key_preview_enabled"
        const val KEY_THEME = "keyboard_theme"
        const val KEY_FORGET_UNUSED = "forget_unused_predictions"
        const val KEY_LETTER_MODE = "letter_mode"
        const val KEY_BACKGROUND_COLOR = "keyboard_background_color"
        const val KEY_KEY_FILL_COLOR = "keyboard_key_fill_color"
        const val KEY_KEY_BORDER_ENABLED = "keyboard_key_border_enabled"
        const val KEY_KEY_BORDER_COLOR = "keyboard_key_border_color"
        const val KEY_KEY_LABEL_COLOR = "keyboard_key_label_color"
        const val KEY_ALPHA = "keyboard_alpha"
        const val KEY_ANIMATION = "keyboard_key_animation"
        const val KEY_BACKGROUND_IMAGE = "keyboard_background_image"
        const val KEY_CANDIDATE_BAR = "candidate_bar_color"
        const val KEY_CANDIDATE_SELECTED = "candidate_selected_color"
        const val KEY_CANDIDATE_UNSELECTED = "candidate_unselected_color"
        const val KEY_CANDIDATE_TEXT = "candidate_text_color"

        val APPEARANCE_COLOR_KEYS = setOf(
            KEY_BACKGROUND_COLOR,
            KEY_KEY_FILL_COLOR,
            KEY_KEY_BORDER_COLOR,
            KEY_KEY_LABEL_COLOR,
            KEY_CANDIDATE_BAR,
            KEY_CANDIDATE_SELECTED,
            KEY_CANDIDATE_UNSELECTED,
            KEY_CANDIDATE_TEXT,
        )

        val APPEARANCE_KEYS = APPEARANCE_COLOR_KEYS + setOf(
            KEY_KEY_BORDER_ENABLED,
            KEY_ALPHA,
            KEY_ANIMATION,
            KEY_BACKGROUND_IMAGE,
        )
    }
}
