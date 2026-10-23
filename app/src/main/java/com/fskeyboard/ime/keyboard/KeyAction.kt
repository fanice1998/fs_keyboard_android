package com.fskeyboard.ime.keyboard

/**
 * [ZHUYIN] and [LATIN] are letter keyboards. [SYMBOLS] is the 123 page and
 * [SYMBOLS_MORE] the #+= page; both return to the letter keyboard they were opened from.
 * [NUMBER] is the digit pad. A field opens it from its input class. No key switches to it.
 */
internal enum class KeyboardMode {
    ZHUYIN,
    LATIN,
    SYMBOLS,
    SYMBOLS_MORE,
    NUMBER,
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
     * [nearby] is what [TouchModel] made of the touch that typed it, [label] included.
     * The composer turns the likely neighbours into extra lexicon readings.
     * Empty when the key did not come from a touch.
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

    /**
     * A computer key from a tap or a swipe on an English letter.
     * [repeating] is a held modifier sending its own key, instead of arming the next letter.
     */
    data class Computer(val key: ComputerKey, val repeating: Boolean = false) : KeyAction
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
    /** Held while the finger stays down, instead of the tap action. */
    val holdKey: ComputerKey? = null,
    /** Computer keys reached by swiping this key. Empty keeps a normal tap. */
    val swipes: Map<SwipeDirection, ComputerKey> = emptyMap(),
    /** Swipe actions that are not computer keys, such as switching language. */
    val extraSwipes: Map<SwipeDirection, KeyAction> = emptyMap(),
)

internal fun KeySpec.swipeAction(direction: SwipeDirection): KeyAction? {
    extraSwipes[direction]?.let { return it }
    return swipes[direction]?.let { KeyAction.Computer(it) }
}

internal fun KeySpec.swipeLabels(): Map<SwipeDirection, String> {
    val labels = linkedMapOf<SwipeDirection, String>()
    for ((direction, key) in swipes) labels[direction] = key.label
    for ((direction, action) in extraSwipes) {
        val text = when (action) {
            is KeyAction.Computer -> action.key.label
            is KeyAction.SetMode -> if (action.mode == KeyboardMode.ZHUYIN) "注" else "En"
            else -> null
        }
        if (text != null) labels[direction] = text
    }
    return labels
}

internal data class KeyRow(
    val keys: List<KeySpec>,
    /** Empty key-widths before the first key. */
    val leading: Float = 0f,
    /** Empty key-widths after the last key. */
    val trailing: Float = 0f,
    /**
     * Empty key-widths before [splitAt], so a landscape row can sit in two
     * clusters. 0 keeps the keys in one run.
     */
    val splitGap: Float = 0f,
    val splitAt: Int = 0,
)
