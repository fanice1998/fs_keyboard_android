package com.fskeyboard.ime.keyboard

internal object KeyboardLayouts {
    fun rows(mode: KeyboardMode, shifted: Boolean = false): List<KeyRow> = when (mode) {
        KeyboardMode.ZHUYIN -> zhuyin()
        KeyboardMode.LATIN -> latin(shifted)
        KeyboardMode.SYMBOLS -> symbols()
    }

    fun letterKeys(mode: KeyboardMode, shifted: Boolean = false): Set<Char> {
        return rows(mode, shifted)
            .flatMap { it.keys }
            .mapNotNull { (it.action as? KeyAction.Character)?.value }
            .map { it.lowercaseChar() }
            .filter { it in 'a'..'z' }
            .toSet()
    }

    private fun zhuyin(): List<KeyRow> {
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
                KeySpec("平聲", KeyAction.Space, weight = 4.6f, role = KeyRole.SPACE),
                iconKey(KeyIcon.RETURN, KeyAction.Enter, 1.85f),
            ),
        )
    }

    private fun latin(shifted: Boolean): List<KeyRow> = listOf(
        KeyRow(letters("qwertyuiop", shifted)),
        KeyRow(letters("asdfghjkl", shifted), leading = 0.5f, trailing = 0.5f),
        KeyRow(listOf(shift(shifted)) + letters("zxcvbnm", shifted) + deleteKey()),
        KeyRow(
            listOf(
                iconKey(KeyIcon.SYMBOLS, KeyAction.SetMode(KeyboardMode.SYMBOLS), 1.35f),
                iconKey(
                    icon = KeyIcon.GLOBE,
                    action = KeyAction.SetMode(KeyboardMode.ZHUYIN),
                    weight = 1.15f,
                    secondary = KeyAction.NextInputMethod,
                ),
                KeySpec(",", KeyAction.Character(',')),
                space(),
                KeySpec(".", KeyAction.Character('.')),
                iconKey(KeyIcon.RETURN, KeyAction.Enter, 1.7f),
            ),
        ),
    )

    private fun symbols(): List<KeyRow> = listOf(
        KeyRow(chars("1234567890")),
        KeyRow(chars("@#\$%&*-+()")),
        KeyRow(chars("~`|\\/[]{}") + deleteKey()),
        KeyRow(
            listOf(
                modeKey("注", KeyboardMode.ZHUYIN, 1.5f),
                KeySpec("，", KeyAction.Character('，')),
                space(),
                KeySpec("。", KeyAction.Character('。')),
                iconKey(KeyIcon.RETURN, KeyAction.Enter, 1.7f),
            ),
        ),
    )

    private fun letters(value: String, shifted: Boolean = false): List<KeySpec> {
        return value.map { letter ->
            val shown = if (shifted) letter.uppercaseChar() else letter
            KeySpec(shown.toString(), KeyAction.Character(shown))
        }
    }

    private fun chars(value: String): List<KeySpec> {
        return value.map { KeySpec(it.toString(), KeyAction.Character(it)) }
    }

    private fun modeKey(label: String, mode: KeyboardMode, weight: Float) = KeySpec(
        label = label,
        action = KeyAction.SetMode(mode),
        weight = weight,
        role = KeyRole.MODIFIER,
    )

    private fun shift(active: Boolean) = KeySpec(
        label = "⇧",
        action = KeyAction.Shift,
        weight = 1.4f,
        role = KeyRole.MODIFIER,
        highlighted = active,
    )

    private fun deleteKey() = KeySpec(
        label = "",
        action = KeyAction.Backspace,
        weight = 1.5f,
        role = KeyRole.ACTION,
        icon = KeyIcon.DELETE,
    )

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
