package com.fskeyboard.ime.keyboard

internal enum class KeyboardMode {
    ZHUYIN,
    LATIN,
    SYMBOLS,
}

internal sealed interface KeyAction {
    /** A key that commits [value] as-is. Used by the Latin and symbol layouts. */
    data class Character(val value: Char) : KeyAction

    /**
     * A zhuyin key. [label] is the bopomofo glyph shown and committed when Rime
     * is unavailable. [key] is the ASCII keysym librime's speller accepts.
     */
    data class Bopomofo(val label: String, val key: Char) : KeyAction

    /** Chinese punctuation committed by the IME. It is not sent to Rime. */
    data class Punctuation(val text: String) : KeyAction

    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Shift : KeyAction
    data class SetMode(val mode: KeyboardMode) : KeyAction
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
)

internal data class KeyRow(
    val keys: List<KeySpec>,
    val inset: Float = 0f,
)
