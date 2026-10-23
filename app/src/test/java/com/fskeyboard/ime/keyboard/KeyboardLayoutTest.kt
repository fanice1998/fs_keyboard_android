package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {
    @Test
    fun latinHasTheAlphabetAndZhuyinDoesNotSendLetters() {
        val alphabet = ('a'..'z').toSet()
        assertEquals(alphabet, KeyboardLayouts.letterKeys(KeyboardMode.LATIN))
        assertTrue(KeyboardLayouts.letterKeys(KeyboardMode.ZHUYIN).isEmpty())
    }

    @Test
    fun latinShiftUppercasesLettersAndReturnsToZhuyin() {
        val specs = KeyboardLayouts.rows(KeyboardMode.LATIN, shifted = true)
            .flatMap { it.keys }
        assertTrue(specs.map { it.label }.contains("Q"))
        assertTrue(specs.map { it.label }.contains("⇧"))
        val locked = KeyboardLayouts.rows(KeyboardMode.LATIN, capsLock = true).flatMap { it.keys }
        assertTrue(locked.map { it.label }.contains("Q"))
        assertTrue(locked.map { it.label }.contains("⇪"))
        assertTrue(locked.first { it.action == KeyAction.Shift }.highlighted)
        val search = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            enter = EnterChoice(label = "搜尋", actionId = 3),
        ).flatMap { it.keys }.first { it.action == KeyAction.Enter }
        assertEquals("", search.label)
        assertEquals(KeyIcon.RETURN, search.icon)
        val check = KeyboardLayouts.rows(
            KeyboardMode.LATIN,
            enter = EnterChoice(label = "搜尋", actionId = 3, confirming = true),
        ).flatMap { it.keys }.first { it.action == KeyAction.Enter }
        assertEquals("", check.label)
        assertEquals(KeyIcon.CHECK, check.icon)
        assertTrue(specs.any { it.icon == KeyIcon.GLOBE && it.action == KeyAction.SetMode(KeyboardMode.ZHUYIN) })
    }

    @Test
    fun latinTopNumberRowAddsDedicatedNumberRowWhenEnabled() {
        val enabled = KeyboardLayouts.rows(KeyboardMode.LATIN, latinTopNumberRow = true)
        assertEquals(5, enabled.size)
        val numberRow = enabled[0].keys
        assertEquals("1234567890", numberRow.joinToString("") { it.label })
        for (i in 0..9) {
            assertEquals(KeyAction.Character("1234567890"[i]), numberRow[i].action)
        }
        val topLetterRow = enabled[1].keys
        assertEquals("qwertyuiop", topLetterRow.joinToString("") { it.label })
        assertTrue(topLetterRow.all { it.secondary == null })

        val disabled = KeyboardLayouts.rows(KeyboardMode.LATIN, latinTopNumberRow = false)
        assertEquals(4, disabled.size)
        val disabledTopRow = disabled[0].keys
        assertEquals("qwertyuiop", disabledTopRow.joinToString("") { it.label })
        assertTrue(disabledTopRow.all { it.secondary == null })
    }

    @Test
    fun zhuyinUsesTheStandardPositions() {
        val rows = KeyboardLayouts.rows(KeyboardMode.ZHUYIN)
        val letterRows = rows.take(4).map { row ->
            row.keys.filter { it.action is KeyAction.Bopomofo }
        }
        assertEquals(
            ZhuyinLayout.rows.map { row -> row.map { it.label to it.key } },
            letterRows.map { row ->
                row.map { spec ->
                    val action = spec.action as KeyAction.Bopomofo
                    action.label to action.key
                }
            },
        )
        val toneLabels = rows.flatMap { it.keys }
            .filter { it.role == KeyRole.TONE }
            .map { it.label }
            .toSet()
        assertEquals(setOf("ˇ", "ˋ", "ˊ", "˙"), toneLabels)
        val actions = rows.flatMap { it.keys }.map { it.action }
        assertTrue(actions.contains(KeyAction.Bopomofo("ㄝ", ',')))
        assertTrue(actions.contains(KeyAction.Bopomofo("ㄡ", '.')))
        assertTrue(actions.contains(KeyAction.Bopomofo("ㄥ", '/')))
        assertFalse(actions.contains(KeyAction.Punctuation("，")))
        assertFalse(actions.contains(KeyAction.Punctuation("。")))
        assertFalse(actions.contains(KeyAction.Character(',')))
        assertFalse(actions.contains(KeyAction.Character('.')))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.LATIN)))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.SYMBOLS)))
        assertTrue(actions.contains(KeyAction.Space))
        assertTrue(actions.contains(KeyAction.Backspace))
        assertTrue(actions.contains(KeyAction.Enter))
        val specs = rows.flatMap { it.keys }
        assertEquals("空格", specs.first { it.action == KeyAction.Space }.label)
        assertEquals(KeyIcon.DELETE, specs.first { it.action == KeyAction.Backspace }.icon)
        assertEquals(KeyIcon.RETURN, specs.first { it.action == KeyAction.Enter }.icon)
        assertEquals(KeyIcon.SYMBOLS, specs.first { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS) }.icon)
        val globe = specs.first { it.icon == KeyIcon.GLOBE }
        assertEquals(KeyAction.SetMode(KeyboardMode.LATIN), globe.action)
        assertEquals(KeyAction.NextInputMethod, globe.secondary)
        assertEquals(1f / 3f, rows[1].leading, 0.0001f)
        assertEquals(2f / 3f, rows[1].trailing, 0.0001f)
        assertEquals(2f / 3f, rows[2].leading, 0.0001f)
        assertEquals(1f / 3f, rows[2].trailing, 0.0001f)
        assertEquals(0f, rows[0].leading, 0f)
        assertEquals(0f, rows[3].leading, 0f)
        assertEquals(0f, rows[3].trailing, 0f)
        assertTrue(rows[3].keys.last().action == KeyAction.Backspace)
    }

    @Test
    fun zhuyinComputerLayoutIsTenColumnsAndLeavesLatinAlone() {
        val rows = KeyboardLayouts.rows(KeyboardMode.ZHUYIN, zhuyinComputerLayout = true)
        assertEquals(5, rows.size)
        assertEquals(
            listOf(
                "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢ",
                "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ",
                "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ",
                "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ",
            ),
            rows.take(4).map { row -> row.keys.joinToString("") { it.label } },
        )
        for (row in rows) {
            val width = row.leading + row.trailing + row.keys.sumOf { it.weight.toDouble() }.toFloat()
            assertEquals(10f, width, 0.001f)
            assertEquals(0f, row.leading, 0f)
            assertEquals(0f, row.trailing, 0f)
        }
        val phonetic = rows.take(4).flatMap { it.keys }
        assertTrue(phonetic.none { it.action == KeyAction.Backspace })
        assertTrue(phonetic.all { it.secondary == null && it.holdKey == null && it.swipes.isEmpty() })
        val bottom = rows.last().keys
        assertEquals(listOf("?123", ",", "", "注音", "ㄦ", "", ""), bottom.map { it.label })
        assertEquals(KeyAction.SetMode(KeyboardMode.SYMBOLS), bottom[0].action)
        assertEquals(null, bottom[0].icon)
        assertEquals(KeyAction.Punctuation(","), bottom[1].action)
        assertEquals(null, bottom[1].secondary)
        assertEquals(KeyIcon.GLOBE, bottom[2].icon)
        assertEquals(KeyAction.SetMode(KeyboardMode.LATIN), bottom[2].action)
        assertEquals(KeyAction.NextInputMethod, bottom[2].secondary)
        assertTrue(bottom[2].swipes.isEmpty())
        assertEquals(KeyAction.Space, bottom[3].action)
        assertEquals(KeyAction.Bopomofo("ㄦ", '-'), bottom[4].action)
        assertEquals(KeyIcon.DELETE, bottom[5].icon)
        assertEquals(KeyAction.Backspace, bottom[5].action)
        assertEquals(KeyIcon.RETURN, bottom[6].icon)
        val bopomofo = rows.flatMap { it.keys }.mapNotNull { it.action as? KeyAction.Bopomofo }
        assertEquals(ZhuyinLayout.keys.size, bopomofo.size)
        assertEquals(
            ZhuyinLayout.keys.map { it.label to it.key }.toSet(),
            bopomofo.map { it.label to it.key }.toSet(),
        )
        assertFalse(rows.flatMap { it.keys }.any { it.action is KeyAction.Character })
        assertEquals(
            KeyboardLayouts.rows(KeyboardMode.LATIN),
            KeyboardLayouts.rows(KeyboardMode.LATIN, zhuyinComputerLayout = true),
        )
        assertEquals(
            KeyboardLayouts.rows(KeyboardMode.ZHUYIN),
            KeyboardLayouts.rows(KeyboardMode.ZHUYIN, zhuyinComputerLayout = false),
        )
    }

    @Test
    fun landscapeSplitsTheLetterKeyboardsAndKeepsTheNumberPadWhole() {
        val zhuyin = KeyboardLayouts.rows(KeyboardMode.ZHUYIN, split = true)
        assertEquals(5, zhuyin.size)
        assertEquals(6, zhuyin[0].splitAt)
        assertEquals(SplitLayout.SIDE, zhuyin[0].splitX.first(), 0.0001f)
        assertEquals(SplitLayout.RIGHT, zhuyin[0].splitX[6], 0.0001f)
        assertEquals(SplitLayout.KEY, zhuyin[0].splitW.first(), 0.0001f)
        assertEquals("ㄅㄉˇˋㄓˊ", zhuyin[0].keys.take(6).joinToString("") { it.label })
        assertEquals("˙ㄚㄞㄢㄦ", zhuyin[0].keys.drop(6).joinToString("") { it.label })
        assertEquals("ㄆㄊㄍㄐㄔ", zhuyin[1].keys.take(5).joinToString("") { it.label })
        assertEquals("ㄗㄧㄛㄟㄣ", zhuyin[1].keys.drop(5).joinToString("") { it.label })
        assertEquals(SplitLayout.leftOrigin(1), zhuyin[1].splitX.first(), 0.0001f)
        assertEquals(SplitLayout.rightOrigin(1), zhuyin[1].splitX[5], 0.0001f)
        assertEquals(KeyAction.Backspace, zhuyin[3].keys.last().action)
        val bottom = zhuyin.last()
        assertEquals(KeyIcon.SYMBOLS, bottom.keys.first().icon)
        assertEquals(KeyAction.SetMode(KeyboardMode.LATIN), bottom.keys[1].action)
        assertEquals(KeyAction.NextInputMethod, bottom.keys[1].secondary)
        assertEquals(2, bottom.keys.count { it.action == KeyAction.Space })
        val right = bottom.keys.drop(bottom.splitAt)
        assertEquals(KeyAction.SetMode(KeyboardMode.SYMBOLS), right[1].action)
        assertEquals(KeyAction.Enter, right.last().action)
        val bopomofo = zhuyin.flatMap { it.keys }.mapNotNull { it.action as? KeyAction.Bopomofo }
        assertEquals(
            ZhuyinLayout.keys.map { it.label to it.key }.toSet(),
            bopomofo.map { it.label to it.key }.toSet(),
        )
        assertTrue(KeyboardLayouts.rows(KeyboardMode.ZHUYIN).all { it.splitGap == 0f })

        val computer = KeyboardLayouts.rows(
            KeyboardMode.ZHUYIN,
            zhuyinComputerLayout = true,
            split = true,
        )
        assertEquals("ㄅㄉˇˋㄓ", computer[0].keys.take(5).joinToString("") { it.label })
        assertEquals("ˊ˙ㄚㄞㄢ", computer[0].keys.drop(5).joinToString("") { it.label })
        val computerRight = computer.last().keys.drop(computer.last().splitAt)
        assertEquals(KeyAction.Bopomofo("ㄦ", '-'), computerRight[1].action)
        assertEquals(KeyAction.Punctuation(","), computer.last().keys[1].action)

        val latin = KeyboardLayouts.rows(KeyboardMode.LATIN, split = true)
        assertEquals("qwert", latin[1].keys.take(5).joinToString("") { it.label })
        assertEquals("yuiop", latin[1].keys.drop(5).joinToString("") { it.label })
        assertEquals("asdfg", latin[2].keys.take(5).joinToString("") { it.label })
        assertEquals("hjkl", latin[2].keys.drop(5).joinToString("") { it.label })
        assertEquals(2, latin.last().keys.count { it.action == KeyAction.Space })
        val letters = latin.flatMap { it.keys }
            .mapNotNull { (it.action as? KeyAction.Character)?.value?.lowercaseChar() }
            .filter { it in 'a'..'z' }
            .toSet()
        assertEquals(('a'..'z').toSet(), letters)
        assertEquals(
            KeyboardLayouts.rows(KeyboardMode.LATIN),
            KeyboardLayouts.rows(KeyboardMode.LATIN, split = false),
        )

        val symbols = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = KeyboardMode.ZHUYIN, split = true)
        assertEquals("12345", symbols[0].keys.take(5).joinToString("") { it.label })
        assertEquals("67890", symbols[0].keys.drop(5).joinToString("") { it.label })
        assertEquals(SplitLayout.SIDE, symbols[0].splitX.first(), 0.0001f)
        assertEquals(SplitLayout.RIGHT, symbols[0].splitX[5], 0.0001f)
        assertEquals(
            KeyboardLayouts.rows(KeyboardMode.NUMBER),
            KeyboardLayouts.rows(KeyboardMode.NUMBER, split = true),
        )
        assertTrue(KeyboardLayouts.rows(KeyboardMode.ZHUYIN).all { it.splitX.isEmpty() })
        assertTrue(KeyboardLayouts.rows(KeyboardMode.LATIN).all { it.splitX.isEmpty() })
        assertSplitStaysInsideTheTemplate(
            zhuyin,
            computer,
            latin,
            KeyboardLayouts.rows(KeyboardMode.LATIN, split = true, computerKeys = true),
            symbols,
            KeyboardLayouts.rows(KeyboardMode.SYMBOLS_MORE, base = KeyboardMode.LATIN, split = true),
        )
    }

    private fun assertSplitStaysInsideTheTemplate(vararg layouts: List<KeyRow>) {
        for (rows in layouts) {
            for (row in rows) {
                assertEquals(row.keys.size, row.splitX.size)
                assertEquals(row.keys.size, row.splitW.size)
                assertTrue(row.splitAt in 1 until row.keys.size)
                val leftEnd = row.splitX[row.splitAt - 1] + row.splitW[row.splitAt - 1]
                assertTrue(leftEnd < row.splitX[row.splitAt])
                row.splitX.indices.forEach { index ->
                    val x = row.splitX[index]
                    val width = row.splitW[index]
                    assertTrue(x >= SplitLayout.SIDE - 0.0001f)
                    assertTrue(x + width <= SplitLayout.rightEdge() + 0.0001f)
                    val drawnLeft = SplitLayout.placedX(x, width)
                    val drawnRight = drawnLeft + SplitLayout.placedW(width)
                    assertTrue(drawnLeft > 0f)
                    assertTrue(drawnRight < 1f)
                }
            }
        }
    }

    @Test
    fun symbolsFromZhuyinUseChinesePunctuationAndReturnToZhuyin() {
        val specs = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = KeyboardMode.ZHUYIN)
            .flatMap { it.keys }
        val actions = specs.map { it.action }
        assertTrue(actions.contains(KeyAction.Character('，')))
        assertTrue(actions.contains(KeyAction.Character('。')))
        assertTrue(actions.contains(KeyAction.Character('「')))
        assertTrue(actions.contains(KeyAction.SetMode(KeyboardMode.SYMBOLS_MORE)))
        assertEquals("注音", specs.first { it.action == KeyAction.SetMode(KeyboardMode.ZHUYIN) }.label)
        assertEquals(KeyIcon.RETURN, specs.first { it.action == KeyAction.Enter }.icon)

        val rows = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = KeyboardMode.ZHUYIN)
        assertEquals("-/：；（）$@「」", rows[1].keys.joinToString("") { it.label })
        assertEquals(listOf("#+=", "。", "，", "、", "？", "！", ".", ""), rows[2].keys.map { it.label })
        val more = KeyboardLayouts.rows(KeyboardMode.SYMBOLS_MORE, base = KeyboardMode.ZHUYIN)
        assertEquals("_—\\|～«»¥&·", more[1].keys.joinToString("") { it.label })
        assertTrue(more[2].keys.any { it.action == KeyAction.Punctuation("^_^") })
    }

    @Test
    fun halfWidthZhuyinSymbolsMatchLatinButReturnToZhuyin() {
        for (mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_MORE)) {
            val half = KeyboardLayouts.rows(mode, base = KeyboardMode.ZHUYIN, fullWidth = false)
            val latin = KeyboardLayouts.rows(mode, base = KeyboardMode.LATIN)
            assertEquals(
                latin.take(3).map { row -> row.keys.map { it.action } },
                half.take(3).map { row -> row.keys.map { it.action } },
            )
            val back = half.flatMap { it.keys }.first { it.label == "注音" }
            assertEquals(KeyAction.SetMode(KeyboardMode.ZHUYIN), back.action)
        }
    }

    @Test
    fun symbolsFromLatinUseAsciiAndReturnToLatin() {
        for (mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_MORE)) {
            val specs = KeyboardLayouts.rows(mode, base = KeyboardMode.LATIN).flatMap { it.keys }
            val back = specs.first { it.role == KeyRole.MODIFIER && it.label == "ABC" }
            assertEquals(KeyAction.SetMode(KeyboardMode.LATIN), back.action)
            assertFalse(specs.any { it.action == KeyAction.Character('，') })
        }
        val numbers = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = KeyboardMode.LATIN)
        assertEquals("1234567890", numbers[0].keys.joinToString("") { it.label })
        assertEquals("-/:;()$&@\"", numbers[1].keys.joinToString("") { it.label })
    }

    @Test
    fun theTwoSymbolPagesSwitchToEachOther() {
        for (base in listOf(KeyboardMode.ZHUYIN, KeyboardMode.LATIN)) {
            val numbers = KeyboardLayouts.rows(KeyboardMode.SYMBOLS, base = base).flatMap { it.keys }
            val more = KeyboardLayouts.rows(KeyboardMode.SYMBOLS_MORE, base = base).flatMap { it.keys }
            assertEquals("#+=", numbers.first { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS_MORE) }.label)
            assertEquals("123", more.first { it.action == KeyAction.SetMode(KeyboardMode.SYMBOLS) }.label)
        }
    }

    @Test
    fun everySymbolRowIsAsWideAsTenKeys() {
        for (base in listOf(KeyboardMode.ZHUYIN, KeyboardMode.LATIN)) {
            for (mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.SYMBOLS_MORE)) {
                for (fullWidth in listOf(true, false)) {
                    val rows = KeyboardLayouts.rows(mode, base = base, fullWidth = fullWidth)
                    for (row in rows.take(3)) {
                        val width = row.keys.sumOf { it.weight.toDouble() }.toFloat()
                        assertEquals("$base $mode $fullWidth", 10f, width, 0.001f)
                    }
                }
            }
        }
    }
}
