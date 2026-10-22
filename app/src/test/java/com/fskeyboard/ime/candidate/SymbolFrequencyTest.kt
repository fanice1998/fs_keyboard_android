package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolFrequencyTest {
    @Test
    fun aSpaceOrNoTextPredictsNothing() {
        val frequency = SymbolFrequency()
        frequency.learn("你好嗎", "？")
        assertTrue(frequency.predict("").isEmpty())
        assertTrue(frequency.predict("你好嗎 ").isEmpty())
        assertTrue(frequency.predict(" ").isEmpty())
        assertFalse(frequency.learn("你好 ", "。"))
    }

    @Test
    fun aTypedSymbolIsOfferedAgainInFrequencyOrder() {
        val frequency = SymbolFrequency()
        frequency.learn("你好嗎", "？")
        frequency.learn("你好嗎", "。")
        frequency.learn("你好嗎", "？")
        assertEquals(listOf("？", "。"), frequency.predict("你好嗎"))
    }

    @Test
    fun theWordBeforeASymbolIsWhatGetsCounted() {
        val frequency = SymbolFrequency()
        assertTrue(frequency.learn("你好嗎？", "？"))
        assertEquals(listOf("？"), frequency.predict("今天也好嗎"))
        assertEquals(listOf("？"), frequency.predict("你好嗎"))
    }

    @Test
    fun aLongerSuffixOutweighsOneShortHabit() {
        val frequency = SymbolFrequency()
        frequency.learn("嗎", "？")
        frequency.learn("你好嗎", "。")
        assertEquals("。", frequency.predict("你好嗎").first())
    }

    @Test
    fun repeatedShortHabitsCanPassOneLongOne() {
        val frequency = SymbolFrequency()
        repeat(7) { frequency.learn("嗎", "？") }
        frequency.learn("你好嗎", "。")
        assertEquals("？", frequency.predict("你好嗎").first())
    }

    @Test
    fun savedLinesRestoreTheCounts() {
        val frequency = SymbolFrequency()
        frequency.learn("好", "！")
        frequency.learn("好", "！")
        val restored = SymbolFrequency()
        restored.load(frequency.serialize())
        assertEquals(listOf("！"), restored.predict("好"))
        assertEquals(frequency.serialize(), restored.serialize())
    }
}
