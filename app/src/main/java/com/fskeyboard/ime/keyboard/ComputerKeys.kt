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
    META("Meta", KeyEvent.KEYCODE_META_LEFT, modifier = true),
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
            else -> 0
        }
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
 * Which computer key a swipe on an English letter sends.
 * Each [ComputerKey] belongs to at most one slot. [assign] refuses a key that already has a slot.
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
     * Puts [key] on this letter and direction.
     * Returns null when the key is already on a different slot, or the letter is not a-z.
     * The same slot may replace its own key, which frees the previous one.
     */
    fun assign(letter: Char, direction: SwipeDirection, key: ComputerKey): ComputerKeyMap? {
        val lower = letter.lowercaseChar()
        if (lower !in 'a'..'z') return null
        val slot = ComputerSlot(lower, direction)
        if (bindings[slot] == key) return this
        val taken = owner(key)
        if (taken != null && taken != slot) return null
        return ComputerKeyMap(bindings + (slot to key))
    }

    fun clear(letter: Char, direction: SwipeDirection): ComputerKeyMap {
        val slot = ComputerSlot(letter.lowercaseChar(), direction)
        if (slot !in bindings) return this
        return ComputerKeyMap(bindings - slot)
    }

    /** `q/up=esc,w/up=tab`. Empty when nothing is assigned. */
    fun encode(): String {
        return bindings.entries
            .sortedWith(compareBy({ it.key.letter }, { it.key.direction.ordinal }))
            .joinToString(",") { (slot, key) ->
                "${slot.letter}/${slot.direction.name.lowercase()}=${key.name.lowercase()}"
            }
    }

    override fun equals(other: Any?): Boolean {
        return other is ComputerKeyMap && bindings == other.bindings
    }

    override fun hashCode(): Int = bindings.hashCode()

    companion object {
        val EMPTY = ComputerKeyMap(emptyMap())

        /** The upward map from before directions could be edited. */
        val DEFAULT = ComputerKeyMap(
            mapOf(
                ComputerSlot('q', SwipeDirection.UP) to ComputerKey.ESC,
                ComputerSlot('w', SwipeDirection.UP) to ComputerKey.TAB,
                ComputerSlot('e', SwipeDirection.UP) to ComputerKey.CTRL,
                ComputerSlot('r', SwipeDirection.UP) to ComputerKey.ALT,
                ComputerSlot('t', SwipeDirection.UP) to ComputerKey.UP,
                ComputerSlot('y', SwipeDirection.UP) to ComputerKey.DOWN,
                ComputerSlot('u', SwipeDirection.UP) to ComputerKey.LEFT,
                ComputerSlot('i', SwipeDirection.UP) to ComputerKey.RIGHT,
                ComputerSlot('o', SwipeDirection.UP) to ComputerKey.HOME,
                ComputerSlot('p', SwipeDirection.UP) to ComputerKey.END,
                ComputerSlot('a', SwipeDirection.UP) to ComputerKey.PAGE_UP,
                ComputerSlot('s', SwipeDirection.UP) to ComputerKey.PAGE_DOWN,
                ComputerSlot('d', SwipeDirection.UP) to ComputerKey.FORWARD_DELETE,
                ComputerSlot('f', SwipeDirection.UP) to ComputerKey.META,
            ),
        )

        private val directions = SwipeDirection.values().associateBy { it.name.lowercase() }
        private val keys = ComputerKey.values().associateBy { it.name.lowercase() }

        /** Unknown tokens are skipped. A repeated computer key keeps the first slot. */
        fun decode(stored: String): ComputerKeyMap {
            if (stored.isBlank()) return EMPTY
            val bindings = linkedMapOf<ComputerSlot, ComputerKey>()
            val used = mutableSetOf<ComputerKey>()
            for (part in stored.split(',')) {
                val token = part.trim()
                val slotSep = token.indexOf('/')
                val eq = token.indexOf('=')
                if (slotSep <= 0 || eq <= slotSep + 1 || eq >= token.length - 1) continue
                val letter = token.substring(0, slotSep).singleOrNull()?.lowercaseChar() ?: continue
                if (letter !in 'a'..'z') continue
                val direction = directions[token.substring(slotSep + 1, eq).trim().lowercase()] ?: continue
                val key = keys[token.substring(eq + 1).trim().lowercase()] ?: continue
                if (!used.add(key)) continue
                bindings[ComputerSlot(letter, direction)] = key
            }
            return ComputerKeyMap(bindings)
        }
    }
}

/**
 * Ctrl, Alt, and Meta selected by a swipe.
 * Tapping the same one again removes it. A following key consumes the set.
 */
internal class ComputerModifiers {
    private val armed = linkedSetOf<ComputerKey>()

    fun active(): Set<ComputerKey> = armed.toSet()

    fun isActive(): Boolean = armed.isNotEmpty()

    fun toggle(key: ComputerKey) {
        if (!key.modifier) return
        if (!armed.add(key)) armed.remove(key)
    }

    fun clear() {
        armed.clear()
    }

    fun metaState(shift: Boolean = false): Int {
        var meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        for (key in armed) meta = meta or key.metaState
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
