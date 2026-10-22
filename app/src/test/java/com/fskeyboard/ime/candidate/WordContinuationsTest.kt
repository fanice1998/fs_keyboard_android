package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WordContinuationsTest {
    @Test
    fun theMostLikelyNextWordComesFirst() {
        val words = continuations(
            Triple("我們", "說", -0.2f),
            Triple("我們", "一個", -1.4f),
            Triple("了", "吧", -0.1f),
            Triple("了", "八", -2.0f),
        )
        assertEquals(listOf("說", "一個"), words.following("我們"))
        assertEquals(listOf("吧", "八"), words.following("你好了"))
        assertEquals(listOf("說"), words.following("我們", limit = 1))
    }

    @Test
    fun theLongestDictionaryWordTouchingTheCaretWins() {
        val words = continuations(
            Triple("天氣", "很好", -0.3f),
            Triple("氣", "溫", -0.4f),
        )
        assertEquals(listOf("很好"), words.following("今天天氣"))
        assertEquals(listOf("溫"), words.following("氣"))
    }

    @Test
    fun aSpaceOrNoHanPredictsNothing() {
        val words = continuations(Triple("我們", "說", -0.2f))
        assertTrue(words.following("").isEmpty())
        assertTrue(words.following("我們 ").isEmpty())
        assertTrue(words.following(" ").isEmpty())
        assertTrue(words.following("hello").isEmpty())
        assertTrue(words.following("我們。").isEmpty())
        assertTrue(words.following("龘").isEmpty())
    }

    @Test
    fun onlyTheLastEightHanCharactersAreTheContext() {
        val tail = "二三四五六七八九"
        val words = continuations(
            Triple("甲$tail", "全", -0.1f),
            Triple(tail, "好", -0.2f),
        )
        assertEquals(listOf("好"), words.following("甲$tail"))
        assertTrue(words.following(tail + "甲").isEmpty())
    }

    @Test
    fun equalProbabilitiesUseTheWordTextAsATieBreak() {
        val words = continuations(
            Triple("了", "吧", -1f),
            Triple("了", "八", -1f),
        )
        assertEquals(listOf("八", "吧"), words.following("了"))
    }

    @Test
    fun aBadLexiconIsIgnored() {
        assertNull(WordContinuations.open(byteArrayOf()))
        assertNull(WordContinuations.open("FSLX".toByteArray()))
        val words = continuations(Triple("我們", "說", -0.2f))
        val broken = compiled(Triple("我們", "說", -0.2f)).copyOf(20)
        assertNull(WordContinuations.open(broken))
        assertEquals(listOf("說"), words.following("xx我們"))
    }
}

private fun continuations(vararg pairs: Triple<String, String, Float>): WordContinuations {
    return WordContinuations.open(compiled(*pairs))!!
}

/** A tiny FSLX version-2 buffer with just the word table and its pairs. */
private fun compiled(vararg pairs: Triple<String, String, Float>): ByteArray {
    val names = pairs.flatMap { listOf(it.first, it.second) }.distinct().sorted()
    val idOf = names.withIndex().associate { it.value to it.index }
    val runs = names.map { mutableListOf<Pair<Int, Float>>() }
    for ((left, right, probability) in pairs) {
        runs[idOf.getValue(left)].add(idOf.getValue(right) to probability)
    }
    for (run in runs) run.sortBy { it.first }
    val strings = ByteArrayOutputStream()
    val spans = names.map { name ->
        val bytes = name.toByteArray(Charsets.UTF_8)
        val offset = strings.size()
        strings.write(bytes)
        offset to bytes.size
    }
    val stringBytes = strings.toByteArray()
    val buffer = ByteBuffer.allocate(48 + names.size * 16 + pairs.size * 8 + stringBytes.size)
        .order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("FSLX".toByteArray(Charsets.US_ASCII))
    buffer.putInt(2)
    buffer.putInt(0)
    buffer.putInt(0)
    buffer.putInt(0)
    buffer.putInt(0)
    buffer.putInt(names.size)
    buffer.putInt(pairs.size)
    buffer.putInt(0)
    buffer.putInt(stringBytes.size)
    buffer.putInt(0)
    buffer.putInt(0)
    var pairAt = 0
    val pairBytes = ByteBuffer.allocate(pairs.size * 8).order(ByteOrder.LITTLE_ENDIAN)
    for (index in names.indices) {
        val (offset, length) = spans[index]
        buffer.putInt(offset)
        buffer.putInt(length)
        buffer.putInt(pairAt)
        buffer.putInt(runs[index].size)
        for ((next, probability) in runs[index]) {
            pairBytes.putInt(next)
            pairBytes.putFloat(probability)
            pairAt += 1
        }
    }
    buffer.put(pairBytes.array())
    buffer.put(stringBytes)
    check(buffer.position() == buffer.capacity())
    return buffer.array()
}
