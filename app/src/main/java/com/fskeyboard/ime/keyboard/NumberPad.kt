package com.fskeyboard.ime.keyboard

import android.text.InputType

/**
 * Which digit pad a field wants, if any.
 * [from] is the only way onto this keyboard. Letter and symbol keys never select it.
 * A phone or date field gets the same digits. A minus or a decimal point appears
 * only when that number field asks for one.
 */
internal data class NumberPad(
    val signed: Boolean = false,
    val decimal: Boolean = false,
) {
    companion object {
        val NONE = NumberPad()

        fun from(inputType: Int): NumberPad? {
            val clazz = inputType and InputType.TYPE_MASK_CLASS
            if (clazz != InputType.TYPE_CLASS_NUMBER &&
                clazz != InputType.TYPE_CLASS_PHONE &&
                clazz != InputType.TYPE_CLASS_DATETIME
            ) {
                return null
            }
            val number = clazz == InputType.TYPE_CLASS_NUMBER
            return NumberPad(
                signed = number && inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                decimal = number && inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
            )
        }
    }
}
