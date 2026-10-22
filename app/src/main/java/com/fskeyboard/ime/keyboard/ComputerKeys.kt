package com.fskeyboard.ime.keyboard

import android.view.KeyEvent
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Computer keys and symbols reached by swiping an English letter.
 * [modifier] keys wait for the next key instead of sending themselves.
 * A [symbol] is committed as that character.
 */
internal enum class ComputerKey(
    val label: String,
    val keyCode: Int,
    val modifier: Boolean = false,
    val symbol: Char? = null,
) {
    ESC("Esc", KeyEvent.KEYCODE_ESCAPE),
    TAB("Tab", KeyEvent.KEYCODE_TAB),
    CTRL("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, modifier = true),
    ALT("Alt", KeyEvent.KEYCODE_ALT_LEFT, modifier = true),
    UP("↑", KeyEvent.KEYCODE_DPAD_UP),
    DOWN("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("←", KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    HOME("Home", KeyEvent.KEYCODE_MOVE_HOME),
    END("End", KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
    FORWARD_DELETE("Del", KeyEvent.KEYCODE_FORWARD_DEL),
    META("⌘", KeyEvent.KEYCODE_META_LEFT, modifier = true),
    FN("Fn", KeyEvent.KEYCODE_FUNCTION, modifier = true),
    BANG("!", KeyEvent.KEYCODE_UNKNOWN, symbol = '!'),
    AT("@", KeyEvent.KEYCODE_UNKNOWN, symbol = '@'),
    HASH("#", KeyEvent.KEYCODE_UNKNOWN, symbol = '#'),
    DOLLAR("$", KeyEvent.KEYCODE_UNKNOWN, symbol = '$'),
    PERCENT("%", KeyEvent.KEYCODE_UNKNOWN, symbol = '%'),
    CARET("^", KeyEvent.KEYCODE_UNKNOWN, symbol = '^'),
    AMPERSAND("&", KeyEvent.KEYCODE_UNKNOWN, symbol = '&'),
    STAR("*", KeyEvent.KEYCODE_UNKNOWN, symbol = '*'),
    PAREN_LEFT("(", KeyEvent.KEYCODE_UNKNOWN, symbol = '('),
    PAREN_RIGHT(")", KeyEvent.KEYCODE_UNKNOWN, symbol = ')'),
    BRACE_LEFT("{", KeyEvent.KEYCODE_UNKNOWN, symbol = '{'),
    BRACE_RIGHT("}", KeyEvent.KEYCODE_UNKNOWN, symbol = '}'),
    BRACKET_LEFT("[", KeyEvent.KEYCODE_UNKNOWN, symbol = '['),
    BRACKET_RIGHT("]", KeyEvent.KEYCODE_UNKNOWN, symbol = ']'),
    GRAVE("`", KeyEvent.KEYCODE_UNKNOWN, symbol = '`'),
    TILDE("~", KeyEvent.KEYCODE_UNKNOWN, symbol = '~'),
    MINUS("-", KeyEvent.KEYCODE_UNKNOWN, symbol = '-'),
    UNDERSCORE("_", KeyEvent.KEYCODE_UNKNOWN, symbol = '_'),
    EQUALS("=", KeyEvent.KEYCODE_UNKNOWN, symbol = '='),
    PLUS("+", KeyEvent.KEYCODE_UNKNOWN, symbol = '+'),
    BACKSLASH("\\", KeyEvent.KEYCODE_UNKNOWN, symbol = '\\'),
    PIPE("|", KeyEvent.KEYCODE_UNKNOWN, symbol = '|'),
    SEMICOLON(";", KeyEvent.KEYCODE_UNKNOWN, symbol = ';'),
    COLON(":", KeyEvent.KEYCODE_UNKNOWN, symbol = ':'),
    QUOTE("'", KeyEvent.KEYCODE_UNKNOWN, symbol = '\''),
    DOUBLE_QUOTE("\"", KeyEvent.KEYCODE_UNKNOWN, symbol = '"'),
    COMMA(",", KeyEvent.KEYCODE_UNKNOWN, symbol = ','),
    PERIOD(".", KeyEvent.KEYCODE_UNKNOWN, symbol = '.'),
    SLASH("/", KeyEvent.KEYCODE_UNKNOWN, symbol = '/'),
    LESS("<", KeyEvent.KEYCODE_UNKNOWN, symbol = '<'),
    GREATER(">", KeyEvent.KEYCODE_UNKNOWN, symbol = '>'),
    QUESTION("?", KeyEvent.KEYCODE_UNKNOWN, symbol = '?'),
    ELLIPSIS("…", KeyEvent.KEYCODE_UNKNOWN, symbol = '…'),
    EM_DASH("—", KeyEvent.KEYCODE_UNKNOWN, symbol = '—'),
    MIDDLE_DOT("·", KeyEvent.KEYCODE_UNKNOWN, symbol = '·'),
    DEGREE("°", KeyEvent.KEYCODE_UNKNOWN, symbol = '°'),
    PLUS_MINUS("±", KeyEvent.KEYCODE_UNKNOWN, symbol = '±'),
    TIMES("×", KeyEvent.KEYCODE_UNKNOWN, symbol = '×'),
    DIVIDE("÷", KeyEvent.KEYCODE_UNKNOWN, symbol = '÷'),
    ;

    val metaState: Int
        get() = when (this) {
            CTRL -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            ALT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            META -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            FN -> KeyEvent.META_FUNCTION_ON
            else -> 0
        }

    /** Direction keys repeat while the finger stays in that swipe. */
    val isArrow: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

/** Key code for a typed Latin letter or digit. Punctuation has none. */
internal fun computerKeyCode(value: Char): Int? {
    val lower = value.lowercaseChar()
    if (lower in 'a'..'z') return KeyEvent.KEYCODE_A + (lower - 'a')
    if (value in '0'..'9') return KeyEvent.KEYCODE_0 + (value - '0')
    return null
}

/**
 * Eight slices around a key. [of] uses 0° as visual up and grows clockwise.
 * A movement inside the slop is a tap, so it returns null.
 */
internal enum class SwipeDirection(val arrow: String) {
    UP("↑"),
    UP_RIGHT("↗"),
    RIGHT("→"),
    DOWN_RIGHT("↘"),
    DOWN("↓"),
    DOWN_LEFT("↙"),
    LEFT("←"),
    UP_LEFT("↖"),
    ;

    companion object {
        private val all = values()

        fun of(dx: Float, dy: Float, slop: Float): SwipeDirection? {
            if (dx * dx + dy * dy < slop * slop) return null
            val angle = atan2(dx.toDouble(), (-dy).toDouble())
            val sector = ((angle + PI / 8.0 + 2.0 * PI) % (2.0 * PI) / (PI / 4.0)).toInt()
            return all[sector and 7]
        }
    }
}

/** One letter and one of its eight directions. */
internal data class ComputerSlot(val letter: Char, val direction: SwipeDirection)

/**
 * Which computer key a swipe on an English key sends.
 * Each [ComputerKey] belongs to at most one key. That key may use it on more than one direction.
 * [assign] refuses a key that already belongs to a different key.
 */
internal class ComputerKeyMap private constructor(
    private val bindings: Map<ComputerSlot, ComputerKey>,
) {
    fun swipes(letter: Char): Map<SwipeDirection, ComputerKey> {
        val lower = letter.lowercaseChar()
        val found = linkedMapOf<SwipeDirection, ComputerKey>()
        for ((slot, key) in bindings) {
            if (slot.letter == lower) found[slot.direction] = key
        }
        return found
    }

    fun owner(key: ComputerKey): ComputerSlot? {
        for ((slot, bound) in bindings) {
            if (bound == key) return slot
        }
        return null
    }

    /**
     * Puts [key] on this key and direction.
     * Returns null when the key is already on a different key, or this is not a swipeable key.
     * The same key may use it on more than one direction. Replacing one direction frees nothing else.
     */
    fun assign(letter: Char, direction: SwipeDirection, key: ComputerKey): ComputerKeyMap? {
        val token = swipeToken(letter) ?: return null
        val slot = ComputerSlot(token, direction)
        if (bindings[slot] == key) return this
        val taken = owner(key)
        if (taken != null && taken.letter != token) return null
        return ComputerKeyMap(bindings + (slot to key))
    }

    fun clear(letter: Char, direction: SwipeDirection): ComputerKeyMap {
        val token = swipeToken(letter) ?: return this
        val slot = ComputerSlot(token, direction)
        if (slot !in bindings) return this
        return ComputerKeyMap(bindings - slot)
    }

    /** `q/down_right=esc,./left=left,del/up_right=forward_delete`. Empty when nothing is assigned. */
    fun encode(): String {
        return bindings.entries
            .sortedWith(compareBy({ it.key.letter.code }, { it.key.direction.ordinal }))
            .joinToString(",") { (slot, key) ->
                "${tokenName(slot.letter)}/${slot.direction.name.lowercase()}=${key.name.lowercase()}"
            }
    }

    override fun equals(other: Any?): Boolean {
        return other is ComputerKeyMap && bindings == other.bindings
    }

    override fun hashCode(): Int = bindings.hashCode()

    companion object {
        val EMPTY = ComputerKeyMap(emptyMap())

        /** Delete key in the stored map. It is not a typed character. */
        const val DELETE = '\u0008'

        /** The English computer-key gestures used until the user edits them. */
        val DEFAULT = ComputerKeyMap(
            listOf(
                slot('q', SwipeDirection.DOWN_RIGHT, ComputerKey.ESC),
                slot('a', SwipeDirection.UP_LEFT, ComputerKey.TAB),
                slot('1', SwipeDirection.DOWN_RIGHT, ComputerKey.BANG),
                slot('2', SwipeDirection.DOWN_RIGHT, ComputerKey.AT),
                slot('3', SwipeDirection.DOWN_RIGHT, ComputerKey.HASH),
                slot('4', SwipeDirection.DOWN_RIGHT, ComputerKey.DOLLAR),
                slot('5', SwipeDirection.DOWN_RIGHT, ComputerKey.PERCENT),
                slot('6', SwipeDirection.DOWN_LEFT, ComputerKey.CARET),
                slot('7', SwipeDirection.DOWN_LEFT, ComputerKey.AMPERSAND),
                slot('8', SwipeDirection.DOWN_LEFT, ComputerKey.STAR),
                slot('9', SwipeDirection.DOWN_LEFT, ComputerKey.PAREN_LEFT),
                slot('0', SwipeDirection.DOWN_LEFT, ComputerKey.PAREN_RIGHT),
                slot('w', SwipeDirection.DOWN_LEFT, ComputerKey.DOUBLE_QUOTE),
                slot('g', SwipeDirection.DOWN_LEFT, ComputerKey.UNDERSCORE),
                slot('g', SwipeDirection.UP_RIGHT, ComputerKey.MINUS),
                slot('h', SwipeDirection.DOWN_LEFT, ComputerKey.PLUS),
                slot('h', SwipeDirection.UP_RIGHT, ComputerKey.EQUALS),
                slot('j', SwipeDirection.DOWN_LEFT, ComputerKey.BRACE_LEFT),
                slot('j', SwipeDirection.DOWN_RIGHT, ComputerKey.BRACE_RIGHT),
                slot('k', SwipeDirection.DOWN_LEFT, ComputerKey.BRACKET_LEFT),
                slot('k', SwipeDirection.DOWN_RIGHT, ComputerKey.BRACKET_RIGHT),
                slot('l', SwipeDirection.DOWN_LEFT, ComputerKey.QUOTE),
                slot('l', SwipeDirection.UP_RIGHT, ComputerKey.TILDE),
                slot('z', SwipeDirection.DOWN_LEFT, ComputerKey.BACKSLASH),
                slot('z', SwipeDirection.DOWN_RIGHT, ComputerKey.PIPE),
                slot('c', SwipeDirection.UP_RIGHT, ComputerKey.LESS),
                slot('v', SwipeDirection.UP_RIGHT, ComputerKey.GREATER),
                slot('b', SwipeDirection.DOWN_LEFT, ComputerKey.SLASH),
                slot('b', SwipeDirection.UP_RIGHT, ComputerKey.QUESTION),
                slot('n', SwipeDirection.UP_RIGHT, ComputerKey.COLON),
                slot('n', SwipeDirection.DOWN_RIGHT, ComputerKey.SEMICOLON),
                slot('.', SwipeDirection.LEFT, ComputerKey.LEFT),
                slot('.', SwipeDirection.UP, ComputerKey.UP),
                slot('.', SwipeDirection.RIGHT, ComputerKey.RIGHT),
                slot('.', SwipeDirection.DOWN, ComputerKey.DOWN),
                slot(DELETE, SwipeDirection.UP_RIGHT, ComputerKey.FORWARD_DELETE),
                slot('u', SwipeDirection.UP_LEFT, ComputerKey.HOME),
                slot('d', SwipeDirection.DOWN_RIGHT, ComputerKey.END),
                slot(',', SwipeDirection.DOWN_RIGHT, ComputerKey.META),
            ).toMap(),
        )

        private fun slot(letter: Char, direction: SwipeDirection, key: ComputerKey) =
            ComputerSlot(letter, direction) to key

        private fun tokenName(letter: Char): String = when (letter) {
            DELETE -> "del"
            ',' -> "comma"
            else -> letter.toString()
        }

        private fun parseToken(raw: String): Char? {
            val text = raw.trim()
            if (text.equals("del", ignoreCase = true)) return DELETE
            if (text.equals("comma", ignoreCase = true)) return ','
            if (text.length != 1) return null
            return swipeToken(text[0])
        }

        private val directions = SwipeDirection.values().associateBy { it.name.lowercase() }
        private val keys = ComputerKey.values().associateBy { it.name.lowercase() }

        /**
         * Unknown tokens are skipped.
         * The same computer key may repeat on one key. A different key keeps the first one.
         */
        fun decode(stored: String): ComputerKeyMap {
            if (stored.isBlank()) return EMPTY
            val bindings = linkedMapOf<ComputerSlot, ComputerKey>()
            for (part in stored.split(',')) {
                val token = part.trim()
                val slotSep = token.indexOf('/')
                val eq = token.indexOf('=')
                if (slotSep <= 0 || eq <= slotSep + 1 || eq >= token.length - 1) continue
                val letter = parseToken(token.substring(0, slotSep)) ?: continue
                val direction = directions[token.substring(slotSep + 1, eq).trim().lowercase()] ?: continue
                val key = keys[token.substring(eq + 1).trim().lowercase()] ?: continue
                val slot = ComputerSlot(letter, direction)
                val owner = bindings.entries.firstOrNull { it.value == key }?.key
                if (owner != null && owner.letter != letter) continue
                bindings[slot] = key
            }
            return ComputerKeyMap(bindings)
        }
    }
}

/** Letters, digits, comma, period, and the delete key can own swipe directions. */
internal fun swipeToken(value: Char): Char? {
    if (value == ComputerKeyMap.DELETE) return value
    val token = value.lowercaseChar()
    if (token in 'a'..'z' || token in '0'..'9' || token == '.' || token == ',') return token
    return null
}

/** Token edited from the settings keyboard, or null when that key has no directions. */
internal fun swipeKeyToken(spec: KeySpec): Char? {
    val action = spec.action
    if (action is KeyAction.Character) return swipeToken(action.value)
    if (action == KeyAction.Backspace) return ComputerKeyMap.DELETE
    return null
}

/**
 * Ctrl, Alt, Fn, and Command selected by a tap or a swipe.
 * A tap arms the modifier until the next key.
 * Holding for the long-press delay locks it, and it stays after the finger lifts until that key is tapped again.
 */
internal class ComputerModifiers {
    private val armed = linkedSetOf<ComputerKey>()
    private val held = linkedSetOf<ComputerKey>()

    fun active(): Set<ComputerKey> = (armed + held).toSet()

    /** Modifiers locked by a hold. They stay until [hold] is called with down false. */
    fun locked(): Set<ComputerKey> = held.toSet()

    fun isActive(): Boolean = armed.isNotEmpty() || held.isNotEmpty()

    fun hasLatched(): Boolean = armed.isNotEmpty()

    fun toggle(key: ComputerKey) {
        if (!key.modifier) return
        if (!armed.add(key)) armed.remove(key)
    }

    /** Locks [key], or unlocks it and drops a one-shot of the same key. */
    fun hold(key: ComputerKey, down: Boolean) {
        if (!key.modifier) return
        if (down) {
            held.add(key)
        } else {
            held.remove(key)
            armed.remove(key)
        }
    }

    /** Drops one-shot modifiers. A finger that is still down keeps its modifier. */
    fun clearLatched() {
        armed.clear()
    }

    fun clear() {
        armed.clear()
        held.clear()
    }

    fun metaState(shift: Boolean = false): Int {
        var meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        for (key in armed) meta = meta or key.metaState
        for (key in held) meta = meta or key.metaState
        return meta
    }
}

/**
 * Travel from the key's touch-down point.
 * Movement inside [slop] stays a tap. Past that, the direction is one of eight slices.
 */
internal class KeySwipe(private val slop: Float) {
    var direction: SwipeDirection? = null
        private set

    fun move(dx: Float, dy: Float): SwipeDirection? {
        direction = SwipeDirection.of(dx, dy, slop)
        return direction
    }
}
