package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchModelTest {
    // Three keys in a row, 100 wide and 120 tall: ㄏ, ㄎ, ㄑ.
    private val keys = listOf("ㄏ", "ㄎ", "ㄑ").mapIndexed { index, label ->
        KeyArea(label, centerX = 50f + index * 100f, centerY = 60f, width = 100f, height = 120f)
    }

    @Test
    fun aTouchAtTheCenterMeansThatKey() {
        val found = TouchModel.nearby(150f, 60f, keys)
        assertEquals(listOf("ㄎ"), found.map { it.label })
        assertTrue(found[0].probability > 0.95f)
    }

    @Test
    fun aTouchOnTheBorderSplitsBetweenTheTwoKeys() {
        val found = TouchModel.nearby(100f, 60f, keys)
        assertEquals(setOf("ㄏ", "ㄎ"), found.map { it.label }.toSet())
        assertEquals(0.5f, found[0].probability, 0.01f)
        assertEquals(0.5f, found[1].probability, 0.01f)
    }

    @Test
    fun aTouchNearTheEdgeKeepsTheKeyFirstAndOffersTheNeighbour() {
        val found = TouchModel.nearby(120f, 60f, keys)
        assertEquals(listOf("ㄎ", "ㄏ"), found.map { it.label })
        assertTrue(found[0].probability > found[1].probability)
        assertTrue(found.all { it.probability >= TouchModel.MIN_PROBABILITY })
    }

    @Test
    fun aTouchFarFromEveryKeyOffersNothing() {
        assertTrue(TouchModel.nearby(5_000f, 5_000f, keys).isEmpty())
    }

    @Test
    fun atMostThreeKeysAreOffered() {
        val wide = keys.map { it.copy(width = 10_000f, height = 10_000f) } +
            KeyArea("ㄒ", centerX = 350f, centerY = 60f, width = 10_000f, height = 10_000f)
        assertEquals(TouchModel.MAX_KEYS, TouchModel.nearby(200f, 60f, wide).size)
    }
}
