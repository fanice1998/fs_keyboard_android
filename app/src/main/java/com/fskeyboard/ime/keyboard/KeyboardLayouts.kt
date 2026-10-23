package com.fskeyboard.ime.keyboard

internal object KeyboardLayouts {
    /** Five keys, a gap, and five keys. */
    private const val LATIN_SPLIT_COLUMNS = 12f

    /** Six keys, a gap, and five keys. */
    private const val ZHUYIN_SPLIT_COLUMNS = 14f

    /**
     * [base] is the letter keyboard the symbol pages were opened from.
     * [fullWidth] picks the Chinese symbol pages when that keyboard is zhuyin.
     */
    fun rows(
        mode: KeyboardMode,
        shifted: Boolean = false,
        base: KeyboardMode = KeyboardMode.ZHUYIN,
        fullWidth: Boolean = true,
        capsLock: Boolean = false,
        enter: EnterChoice = EnterChoice(),
        latinTopNumberRow: Boolean = true,
        computerKeys: Boolean = false,
        computerMap: ComputerKeyMap = ComputerKeyMap.DEFAULT,
        numberPad: NumberPad = NumberPad.NONE,
        zhuyinComputerLayout: Boolean = false,
        split: Boolean = false,
    ): List<KeyRow> = when (mode) {
        KeyboardMode.ZHUYIN -> when {
            !split -> if (zhuyinComputerLayout) zhuyinComputer(enter) else zhuyin(enter)
            zhuyinComputerLayout -> zhuyinComputerSplit(enter)
            else -> zhuyinSplit(enter)
        }
        KeyboardMode.LATIN -> if (split) {
            latinSplit(shifted, capsLock, enter, latinTopNumberRow, computerKeys, computerMap)
        } else {
            latin(shifted, capsLock, enter, latinTopNumberRow, computerKeys, computerMap)
        }
        KeyboardMode.SYMBOLS -> symbols(base, more = false, fullWidth, enter, split)
        KeyboardMode.SYMBOLS_MORE -> symbols(base, more = true, fullWidth, enter, split)
        KeyboardMode.NUMBER -> number(enter, numberPad)
    }

    fun letterKeys(mode: KeyboardMode, shifted: Boolean = false): Set<Char> {
        return rows(mode, shifted)
            .flatMap { it.keys }
            .mapNotNull { (it.action as? KeyAction.Character)?.value }
            .map { it.lowercaseChar() }
            .filter { it in 'a'..'z' }
            .toSet()
    }

    private fun zhuyin(enter: EnterChoice): List<KeyRow> {
        val phonetic = ZhuyinLayout.rows.mapIndexed { index, row ->
            // Ten keys share the eleven-key pitch. iOS splits the spare key
            // one-to-two, then mirrors that split on the next row.
            val (leading, trailing) = when (index) {
                1 -> (1f / 3f) to (2f / 3f)
                2 -> (2f / 3f) to (1f / 3f)
                else -> 0f to 0f
            }
            KeyRow(
                keys = row.map { key ->
                    KeySpec(
                        label = key.label,
                        action = KeyAction.Bopomofo(key.label, key.key),
                        role = if (key.tone) KeyRole.TONE else KeyRole.NORMAL,
                    )
                },
                leading = leading,
                trailing = trailing,
            )
        }
        val last = phonetic.last()
        val withDelete = phonetic.dropLast(1) +
            KeyRow(last.keys + deleteKey(), leading = last.leading, trailing = last.trailing)
        return withDelete + KeyRow(
            listOf(
                iconKey(KeyIcon.SYMBOLS, KeyAction.SetMode(KeyboardMode.SYMBOLS), 1.45f),
                iconKey(
                    icon = KeyIcon.GLOBE,
                    action = KeyAction.SetMode(KeyboardMode.LATIN),
                    weight = 1.15f,
                    secondary = KeyAction.NextInputMethod,
                ),
                // Labelled as a space bar; while composing it marks 平聲.
                KeySpec("空格", KeyAction.Space, weight = 4.6f, role = KeyRole.SPACE),
                enterKey(enter, 1.85f),
            ),
        )
    }

    /**
     * Daiqian zhuyin in four rows of ten, the arrangement on a computer keyboard.
     * ㄦ sits on the bottom row. The key where an emoji would be is the language key.
     * Corner hints and long-press symbols are not keys yet.
     */
    private fun zhuyinComputer(enter: EnterChoice): List<KeyRow> {
        val phonetic = ZhuyinLayout.rows.mapIndexed { index, row ->
            val keys = if (index == 0) row.dropLast(1) else row
            KeyRow(keys.map { bopomofoKey(it) })
        }
        val er = ZhuyinLayout.rows.first().last()
        return phonetic + KeyRow(
            listOf(
                modeKey("?123", KeyboardMode.SYMBOLS, 1.5f),
                KeySpec(",", KeyAction.Punctuation(","), weight = 0.9f, role = KeyRole.ACTION),
                iconKey(
                    icon = KeyIcon.GLOBE,
                    action = KeyAction.SetMode(KeyboardMode.LATIN),
                    weight = 1.05f,
                    secondary = KeyAction.NextInputMethod,
                ),
                KeySpec("注音", KeyAction.Space, weight = 3.2f, role = KeyRole.SPACE),
                bopomofoKey(er, 0.95f),
                deleteKey().copy(weight = 1f),
                enterKey(enter, 1.4f),
            ),
        )
    }

    private fun bopomofoKey(key: ZhuyinLayout.Key, weight: Float = 1f) = KeySpec(
        label = key.label,
        action = KeyAction.Bopomofo(key.label, key.key),
        weight = weight,
        role = if (key.tone) KeyRole.TONE else KeyRole.NORMAL,
    )

    /**
     * Landscape zhuyin. The left cluster keeps the first six keys of each row
     * and the right cluster keeps the rest, with a gap between them.
     * Each side has a space bar. Symbols stays on the left thumb.
     */
    private fun zhuyinSplit(enter: EnterChoice): List<KeyRow> {
        val phonetic = ZhuyinLayout.rows.mapIndexed { index, row ->
            val keys = row.map { bopomofoKey(it) }
            val right = if (index == ZhuyinLayout.rows.lastIndex) {
                keys.drop(6) + deleteKey().copy(weight = 1f)
            } else {
                keys.drop(6)
            }
            splitRow(keys.take(6), right, ZHUYIN_SPLIT_COLUMNS)
        }
        return phonetic + splitRow(
            listOf(
                iconKey(KeyIcon.SYMBOLS, KeyAction.SetMode(KeyboardMode.SYMBOLS), 1.3f),
                iconKey(
                    icon = KeyIcon.GLOBE,
                    action = KeyAction.SetMode(KeyboardMode.LATIN),
                    weight = 1.15f,
                    secondary = KeyAction.NextInputMethod,
                ),
                KeySpec("空格", KeyAction.Space, weight = 3.55f, role = KeyRole.SPACE),
            ),
            listOf(
                KeySpec("空格", KeyAction.Space, weight = 2.15f, role = KeyRole.SPACE),
                iconKey(KeyIcon.SYMBOLS, KeyAction.SetMode(KeyboardMode.SYMBOLS), 1f),
                enterKey(enter, 1.85f),
            ),
            ZHUYIN_SPLIT_COLUMNS,
        )
    }

    /** Landscape ten-column zhuyin. Five keys a side, and ㄦ stays on the right. */
    private fun zhuyinComputerSplit(enter: EnterChoice): List<KeyRow> {
        val phonetic = ZhuyinLayout.rows.map { row ->
            val keys = if (row.last().label == "ㄦ") row.dropLast(1) else row
            val specs = keys.map { bopomofoKey(it) }
            splitRow(specs.take(5), specs.drop(5), LATIN_SPLIT_COLUMNS)
        }
        val er = ZhuyinLayout.rows.first().last()
        return phonetic + splitRow(
            listOf(
                modeKey("?123", KeyboardMode.SYMBOLS, 1.3f),
                KeySpec(",", KeyAction.Punctuation(","), weight = 0.9f, role = KeyRole.ACTION),
                iconKey(
                    icon = KeyIcon.GLOBE,
                    action = KeyAction.SetMode(KeyboardMode.LATIN),
                    weight = 1.05f,
                    secondary = KeyAction.NextInputMethod,
                ),
                KeySpec("注音", KeyAction.Space, weight = 1.75f, role = KeyRole.SPACE),
            ),
            listOf(
                KeySpec("注音", KeyAction.Space, weight = 1.7f, role = KeyRole.SPACE),
                bopomofoKey(er, 1f),
                deleteKey().copy(weight = 1f),
                enterKey(enter, 1.3f),
            ),
            LATIN_SPLIT_COLUMNS,
        )
    }

    private fun latinSplit(
        shifted: Boolean,
        capsLock: Boolean,
        enter: EnterChoice,
        topNumberRow: Boolean,
        computerKeys: Boolean,
        computerMap: ComputerKeyMap,
    ): List<KeyRow> {
        val upper = shifted || capsLock
        val rows = ArrayList<KeyRow>(5)
        if (topNumberRow) {
            val numbers = symbolKeys(split("1234567890"), computerKeys, computerMap)
            rows += splitRow(numbers.take(5), numbers.drop(5), LATIN_SPLIT_COLUMNS)
        }
        val top = letters("qwertyuiop", upper, computerKeys, computerMap)
        rows += splitRow(top.take(5), top.drop(5), LATIN_SPLIT_COLUMNS)
        val home = letters("asdfghjkl", upper, computerKeys, computerMap)
        rows += splitRow(home.take(5), home.drop(5), LATIN_SPLIT_COLUMNS)
        val bottom = letters("zxcvbnm", upper, computerKeys, computerMap)
        rows += splitRow(
            listOf(shift(upper, capsLock)) + bottom.take(4),
            bottom.drop(4) + deleteKey(computerKeys, computerMap),
            LATIN_SPLIT_COLUMNS,
        )
        val comma = keyed(",", computerKeys, computerMap)
        val period = keyed(".", computerKeys, computerMap)
        val lead = if (computerKeys) {
            listOf(latinCtrlKey(), latinLanguageKey())
        } else {
            listOf(
                iconKey(KeyIcon.SYMBOLS, KeyAction.SetMode(KeyboardMode.SYMBOLS), 1.35f),
                iconKey(
                    icon = KeyIcon.GLOBE,
                    action = KeyAction.SetMode(KeyboardMode.ZHUYIN),
                    weight = 1.15f,
                    secondary = KeyAction.NextInputMethod,
                ),
            )
        }
        rows += splitRow(
            lead + comma + KeySpec("空格", KeyAction.Space, weight = 1.5f, role = KeyRole.SPACE),
            listOf(
                KeySpec("空格", KeyAction.Space, weight = 1.8f, role = KeyRole.SPACE),
                period,
                enterKey(enter, 1.7f),
            ),
            LATIN_SPLIT_COLUMNS,
        )
        return rows
    }

    private fun latin(
        shifted: Boolean,
        capsLock: Boolean,
        enter: EnterChoice,
        topNumberRow: Boolean = true,
        computerKeys: Boolean = false,
        computerMap: ComputerKeyMap = ComputerKeyMap.DEFAULT,
    ): List<KeyRow> {
        val upper = shifted || capsLock
        val rows = ArrayList<KeyRow>(5)
        if (topNumberRow) {
            rows += KeyRow(symbolKeys(split("1234567890"), computerKeys, computerMap))
        }
        rows += KeyRow(letters("qwertyuiop", upper, computerKeys, computerMap))
        rows += KeyRow(letters("asdfghjkl", upper, computerKeys, computerMap), leading = 0.5f, trailing = 0.5f)
        rows += KeyRow(
            listOf(shift(upper, capsLock)) +
                letters("zxcvbnm", upper, computerKeys, computerMap) +
                deleteKey(computerKeys, computerMap),
        )
        val comma = keyed(",", computerKeys, computerMap)
        val period = keyed(".", computerKeys, computerMap)
        val leading = if (computerKeys) {
            listOf(latinCtrlKey(), latinLanguageKey())
        } else {
            listOf(
                iconKey(KeyIcon.SYMBOLS, KeyAction.SetMode(KeyboardMode.SYMBOLS), 1.35f),
                iconKey(
                    icon = KeyIcon.GLOBE,
                    action = KeyAction.SetMode(KeyboardMode.ZHUYIN),
                    weight = 1.15f,
                    secondary = KeyAction.NextInputMethod,
                ),
            )
        }
        rows += KeyRow(leading + comma + space() + period + enterKey(enter, 1.7f))
        return rows
    }

    /**
     * iOS 123 and #+= pages. From zhuyin with [fullWidth] they are the iOS zhuyin
     * pages with full-width Chinese punctuation; otherwise they are the ASCII pages.
     * Either way they return to the letter keyboard they were opened from.
     * On row three the keys between the page key and delete share seven key widths.
     */
    private fun symbols(
        base: KeyboardMode,
        more: Boolean,
        fullWidth: Boolean,
        enter: EnterChoice,
        split: Boolean = false,
    ): List<KeyRow> {
        val chinese = base == KeyboardMode.ZHUYIN && fullWidth
        val (first, second, third) = when {
            !more && !chinese -> Triple(split("1234567890"), split("-/:;()$&@\""), split(".,?!'"))
            !more -> Triple(split("1234567890"), split("-/：；（）$@「」"), split("。，、？！."))
            !chinese -> Triple(split("[]{}#%^*+="), split("_\\|~<>€£¥•"), split(".,?!'"))
            else -> Triple(
                split("[]{}#%^*+="),
                split("_—\\|～«»¥&·"),
                listOf("…", "，", "^_^", "？", "！", "’"),
            )
        }
        val page = if (more) {
            modeKey("123", KeyboardMode.SYMBOLS, 1.5f)
        } else {
            modeKey("#+=", KeyboardMode.SYMBOLS_MORE, 1.5f)
        }
        val zhuyin = base == KeyboardMode.ZHUYIN
        val letters = if (zhuyin) KeyboardMode.ZHUYIN else KeyboardMode.LATIN
        val other = if (zhuyin) KeyboardMode.LATIN else KeyboardMode.ZHUYIN
        val wide = 7f / third.size
        val bottomLead = listOf(
            modeKey(if (zhuyin) "注音" else "ABC", letters, 1.45f),
            iconKey(
                icon = KeyIcon.GLOBE,
                action = KeyAction.SetMode(other),
                weight = 1.15f,
                secondary = KeyAction.NextInputMethod,
            ),
        )
        if (!split) {
            return listOf(
                KeyRow(symbolKeys(first)),
                KeyRow(symbolKeys(second)),
                KeyRow(listOf(page) + symbolKeys(third).map { it.copy(weight = wide) } + deleteKey()),
                KeyRow(
                    bottomLead +
                        KeySpec("空格", KeyAction.Space, weight = 4.6f, role = KeyRole.SPACE) +
                        enterKey(enter, 1.85f),
                ),
            )
        }
        val thirds = symbolKeys(third)
        val mid = thirds.size / 2
        return listOf(
            splitRow(symbolKeys(first).take(5), symbolKeys(first).drop(5), LATIN_SPLIT_COLUMNS),
            splitRow(symbolKeys(second).take(5), symbolKeys(second).drop(5), LATIN_SPLIT_COLUMNS),
            splitRow(
                listOf(page.copy(weight = 1.5f)) + thirds.take(mid),
                thirds.drop(mid) + deleteKey().copy(weight = 1f),
                LATIN_SPLIT_COLUMNS,
            ),
            splitRow(
                bottomLead + KeySpec("空格", KeyAction.Space, weight = 2.4f, role = KeyRole.SPACE),
                listOf(
                    KeySpec("空格", KeyAction.Space, weight = 2.7f, role = KeyRole.SPACE),
                    enterKey(enter, 1.85f),
                ),
                LATIN_SPLIT_COLUMNS,
            ),
        )
    }

    /** Two clusters with at least a key and a half of empty space between them. */
    private fun splitRow(left: List<KeySpec>, right: List<KeySpec>, columns: Float): KeyRow {
        val used = (left + right).sumOf { it.weight.toDouble() }.toFloat()
        val gap = maxOf(1.5f, columns - used)
        return KeyRow(keys = left + right, splitAt = left.size, splitGap = gap)
    }

    /**
     * Digits only. Four columns keep the grid even: delete stays at the top right,
     * minus and the decimal point take that column when the field asks for them,
     * and zero sits under 2. Nothing on this pad changes keyboard.
     */
    private fun number(enter: EnterChoice, pad: NumberPad): List<KeyRow> {
        fun digit(value: Char) = KeySpec(value.toString(), KeyAction.Character(value))
        val delete = deleteKey().copy(weight = 1f)
        return listOf(
            KeyRow(listOf(digit('1'), digit('2'), digit('3'), delete)),
            KeyRow(
                keys = listOf(digit('4'), digit('5'), digit('6')) +
                    if (pad.signed) listOf(digit('-')) else emptyList(),
                trailing = if (pad.signed) 0f else 1f,
            ),
            KeyRow(
                keys = listOf(digit('7'), digit('8'), digit('9')) +
                    if (pad.decimal) listOf(digit('.')) else emptyList(),
                trailing = if (pad.decimal) 0f else 1f,
            ),
            KeyRow(listOf(digit('0')), leading = 1f, trailing = 2f),
            KeyRow(listOf(enterKey(enter, 4f))),
        )
    }

    private fun split(value: String): List<String> = value.map { it.toString() }

    /** One character commits as typed; a longer label such as ^_^ commits as text. */
    private fun symbolKeys(
        labels: List<String>,
        computerKeys: Boolean = false,
        computerMap: ComputerKeyMap = ComputerKeyMap.DEFAULT,
    ): List<KeySpec> = labels.map { label ->
        val action = if (label.length == 1) KeyAction.Character(label[0]) else KeyAction.Punctuation(label)
        val swipes = if (computerKeys && label.length == 1) computerMap.swipes(label[0]) else emptyMap()
        KeySpec(label, action, swipes = swipes)
    }

    private fun keyed(label: String, computerKeys: Boolean, computerMap: ComputerKeyMap): KeySpec {
        val swipes = if (computerKeys) computerMap.swipes(label[0]) else emptyMap()
        return KeySpec(label, KeyAction.Character(label[0]), swipes = swipes)
    }

    private fun latinCtrlKey() = KeySpec(
        label = "Ctrl",
        action = KeyAction.Computer(ComputerKey.CTRL),
        weight = 1.35f,
        role = KeyRole.MODIFIER,
        holdKey = ComputerKey.CTRL,
    )

    /**
     * The language key. A tap switches to zhuyin. Fn is the down-right swipe,
     * and holding that swipe locks it the way Alt locks from the up-left swipe.
     */
    private fun latinLanguageKey() = KeySpec(
        label = "",
        action = KeyAction.SetMode(KeyboardMode.ZHUYIN),
        weight = 1.15f,
        role = KeyRole.ACTION,
        icon = KeyIcon.GLOBE,
        secondary = KeyAction.NextInputMethod,
        swipes = mapOf(
            SwipeDirection.UP_LEFT to ComputerKey.ALT,
            SwipeDirection.DOWN_RIGHT to ComputerKey.FN,
        ),
    )

    private fun letters(
        value: String,
        shifted: Boolean = false,
        computerKeys: Boolean = false,
        computerMap: ComputerKeyMap = ComputerKeyMap.DEFAULT,
    ): List<KeySpec> {
        return value.map { letter ->
            val shown = if (shifted) letter.uppercaseChar() else letter
            val swipes = if (computerKeys) computerMap.swipes(letter) else emptyMap()
            KeySpec(shown.toString(), KeyAction.Character(shown), swipes = swipes)
        }
    }

    private fun modeKey(label: String, mode: KeyboardMode, weight: Float) = KeySpec(
        label = label,
        action = KeyAction.SetMode(mode),
        weight = weight,
        role = KeyRole.MODIFIER,
    )

    private fun shift(active: Boolean, locked: Boolean) = KeySpec(
        label = if (locked) "⇪" else "⇧",
        action = KeyAction.Shift,
        weight = 1.4f,
        role = KeyRole.MODIFIER,
        highlighted = active,
    )

    private fun deleteKey(
        computerKeys: Boolean = false,
        computerMap: ComputerKeyMap = ComputerKeyMap.DEFAULT,
    ) = KeySpec(
        label = "",
        action = KeyAction.Backspace,
        weight = 1.5f,
        role = KeyRole.ACTION,
        icon = KeyIcon.DELETE,
        swipes = if (computerKeys) computerMap.swipes(ComputerKeyMap.DELETE) else emptyMap(),
    )

    private fun enterKey(choice: EnterChoice, weight: Float): KeySpec {
        val icon = if (choice.confirming) KeyIcon.CHECK else KeyIcon.RETURN
        return iconKey(icon, KeyAction.Enter, weight)
    }

    private fun iconKey(
        icon: KeyIcon,
        action: KeyAction,
        weight: Float,
        secondary: KeyAction? = null,
    ) = KeySpec(
        label = "",
        action = action,
        weight = weight,
        role = KeyRole.ACTION,
        icon = icon,
        secondary = secondary,
    )

    private fun space() = KeySpec("空格", KeyAction.Space, weight = 4f, role = KeyRole.SPACE)
}
