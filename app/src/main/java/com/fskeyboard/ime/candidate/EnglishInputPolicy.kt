package com.fskeyboard.ime.candidate

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Determines whether English input should maintain a composing span with word suggestions
 * or directly commit characters without a composing region (following Samsung / AOSP behavior).
 */
internal object EnglishInputPolicy {

    fun shouldCompose(info: EditorInfo?): Boolean {
        if (info == null) return false

        val clazz = info.inputType and InputType.TYPE_MASK_CLASS
        if (clazz != InputType.TYPE_CLASS_TEXT) return false

        if (isPassword(info)) return false

        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        ) {
            return false
        }

        if ((info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
            return false
        }

        return true
    }

    fun isPassword(info: EditorInfo?): Boolean {
        if (info == null) return false
        val clazz = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (clazz == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        if (clazz == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        return false
    }
}
