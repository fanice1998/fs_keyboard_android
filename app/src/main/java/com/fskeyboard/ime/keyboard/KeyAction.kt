package com.fskeyboard.ime.keyboard

/**
 * [ZHUYIN] and [LATIN] are letter keyboards. [SYMBOLS] is the 123 page and
 * [SYMBOLS_MORE] the #+= page; both return to the letter keyboard they were opened from.
 */
internal enum class KeyboardMode {
    ZHUYIN,
    LATIN,
    SYMBOLS,
    SYMBOLS_MORE,
    ;

    val isLetters: Boolean
        get() = this == ZHUYIN || this == LATIN

    val isSymbols: Boolean
        get() = this == SYMBOLS || this == SYMBOLS_MORE
}

internal sealed interface KeyAction {
    /** A key that commits [value] as-is. Used by the Latin and symbol layouts. */
    data class Character(val value: Char) : KeyAction

    /**
     * A zhuyin key. [label] is the bopomofo glyph kept in the composition.
     * [key] is the ASCII position librime's speller accepts, and is never committed.
     * [nearby] is what [TouchModel] made of the touch that typed it, [label] included;
     * empty when the key did not come from a touch.
     */
    data class Bopomofo(
        val label: String,
        val key: Char,
        val nearby: List<NearbyKey> = emptyList(),
    ) : KeyAction

    /** Chinese punctuation committed by the IME. It is not sent to Rime. */
    data class Punctuation(val text: String) : KeyAction

    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Shift : KeyAction
    data class SetMode(val mode: KeyboardMode) : KeyAction

    /** Long-press on the language key: move to the next system keyboard. */
    data object NextInputMethod : KeyAction

    /** A computer key from an upward swipe on an English letter. */
    data class Computer(val key: ComputerKey) : KeyAction
}

internal enum class KeyRole {
    NORMAL,
    MODIFIER,
    SPACE,
    ACTION,
    TONE,
}

internal data class KeySpec(
    val label: String,
    val action: KeyAction,
    val weight: Float = 1f,
    val role: KeyRole = KeyRole.NORMAL,
    val highlighted: Boolean = false,
    val icon: KeyIcon? = null,
    val secondary: KeyAction? = null,
    /** Reached by swiping this key upward. Null keeps a normal tap. */
    val swipeUp: KeyAction? = null,
)

internal data class KeyRow(
    val keys: List<KeySpec>,
    /** Empty key-widths before the first key. */
    val leading: Float = 0f,
    /** Empty key-widths after the last key. */
    val trailing: Float = 0f,
)
