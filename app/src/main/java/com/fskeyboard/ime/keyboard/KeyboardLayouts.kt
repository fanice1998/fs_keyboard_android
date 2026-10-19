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
        val letterRows = ZhuyinLayout.rows.map { row ->
            KeyRow(row.map { key ->
                KeySpec(
                    label = key.label,
                    action = KeyAction.Bopomofo(key.label, key.key),
                    role = if (key.tone) KeyRole.TONE else KeyRole.NORMAL,
                )
            })
        }
        val withBackspace = letterRows.dropLast(1) +
            KeyRow(letterRows.last().keys + backspace())
        return withBackspace + KeyRow(
            listOf(
                modeKey("符", KeyboardMode.SYMBOLS, 1.3f),
                modeKey("英", KeyboardMode.LATIN, 1.3f),
                KeySpec("，", KeyAction.Punctuation("，")),
                space(),
                KeySpec("。", KeyAction.Punctuation("。")),
                enter(),
            ),
        )
    }

    private fun latin(shifted: Boolean): List<KeyRow> = listOf(
        KeyRow(letters("qwertyuiop", shifted)),
        KeyRow(letters("asdfghjkl", shifted), inset = 0.5f),
        KeyRow(listOf(shift(shifted)) + letters("zxcvbnm", shifted) + backspace()),
        KeyRow(
            listOf(
                modeKey("符", KeyboardMode.SYMBOLS, 1.3f),
                modeKey("注", KeyboardMode.ZHUYIN, 1.3f),
                KeySpec(",", KeyAction.Character(',')),
                space(),
                KeySpec(".", KeyAction.Character('.')),
                enter(),
            ),
        ),
    )

    private fun symbols(): List<KeyRow> = listOf(
        KeyRow(chars("1234567890")),
        KeyRow(chars("@#\$%&*-+()")),
        KeyRow(chars("~`|\\/[]{}") + backspace()),
        KeyRow(
            listOf(
                modeKey("返回", KeyboardMode.ZHUYIN, 1.6f),
                KeySpec("，", KeyAction.Character('，')),
                space(),
                KeySpec("。", KeyAction.Character('。')),
                enter(),
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

    private fun backspace() = listOf(
        KeySpec("刪", KeyAction.Backspace, weight = 1.5f, role = KeyRole.ACTION),
    )

    private fun enter() = KeySpec("換行", KeyAction.Enter, weight = 1.5f, role = KeyRole.ACTION)

    private fun space() = KeySpec("空格", KeyAction.Space, weight = 4f, role = KeyRole.SPACE)
}
