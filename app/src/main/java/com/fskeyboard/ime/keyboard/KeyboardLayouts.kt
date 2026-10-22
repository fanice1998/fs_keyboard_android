package com.fskeyboard.ime.keyboard

internal object KeyboardLayouts {
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
    ): List<KeyRow> = when (mode) {
        KeyboardMode.ZHUYIN -> zhuyin(enter)
        KeyboardMode.LATIN -> latin(
            shifted,
            capsLock,
            enter,
            latinTopNumberRow,
            computerKeys,
            computerMap,
        )
        KeyboardMode.SYMBOLS -> symbols(base, more = false, fullWidth, enter)
        KeyboardMode.SYMBOLS_MORE -> symbols(base, more = true, fullWidth, enter)
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
            listOf(latinCtrlKey(), latinFnKey())
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
    private fun symbols(base: KeyboardMode, more: Boolean, fullWidth: Boolean, enter: EnterChoice): List<KeyRow> {
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
        return listOf(
            KeyRow(symbolKeys(first)),
            KeyRow(symbolKeys(second)),
            KeyRow(listOf(page) + symbolKeys(third).map { it.copy(weight = wide) } + deleteKey()),
            KeyRow(
                listOf(
                    modeKey(if (zhuyin) "注音" else "ABC", letters, 1.45f),
                    iconKey(
                        icon = KeyIcon.GLOBE,
                        action = KeyAction.SetMode(other),
                        weight = 1.15f,
                        secondary = KeyAction.NextInputMethod,
                    ),
                    KeySpec("空格", KeyAction.Space, weight = 4.6f, role = KeyRole.SPACE),
                    enterKey(enter, 1.85f),
                ),
            ),
        )
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

    private fun latinFnKey() = KeySpec(
        label = "Fn",
        action = KeyAction.Computer(ComputerKey.FN),
        weight = 1.15f,
        role = KeyRole.MODIFIER,
        holdKey = ComputerKey.FN,
        swipes = mapOf(SwipeDirection.UP_LEFT to ComputerKey.ALT),
        extraSwipes = mapOf(SwipeDirection.DOWN_RIGHT to KeyAction.SetMode(KeyboardMode.ZHUYIN)),
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
