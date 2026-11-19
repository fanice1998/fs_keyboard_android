package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun aSureTouchKeepsGeometricNeighboursForAFailedReading() {
        val center = TouchModel.nearby(150f, 60f, keys)
        assertEquals(listOf("ㄎ"), center.map { it.label })
        val with = TouchModel.withAdjacent(center, "ㄎ", keys)
        assertEquals("ㄎ", with.first().label)
        assertTrue(with.first().probability > 0.95f)
        assertEquals(setOf("ㄏ", "ㄑ"), with.drop(1).map { it.label }.toSet())
        assertTrue(with.drop(1).all { it.probability == 0f })
        val border = TouchModel.nearby(100f, 60f, keys)
        val merged = TouchModel.withAdjacent(border, "ㄎ", keys)
        assertEquals(1, merged.count { it.label == "ㄏ" })
        assertTrue(merged.any { it.label == "ㄑ" && it.probability == 0f })
    }

    @Test
    fun atMostThreeKeysAreOffered() {
        val wide = keys.map { it.copy(width = 10_000f, height = 10_000f) } +
            KeyArea("ㄒ", centerX = 350f, centerY = 60f, width = 10_000f, height = 10_000f)
        assertEquals(TouchModel.MAX_KEYS, TouchModel.nearby(200f, 60f, wide).size)
    }

    @Test
    fun aFinalTwoRowsBelowStaysANeighbour() {
        val areas = portraitZhuyinAreas()
        val names = TouchModel.adjacent("ㄚ", areas)
        assertTrue(names.contains("ㄠ"))
        assertFalse(names.contains("ㄢ"))
        val center = areas.single { it.label == "ㄚ" }
        val sure = TouchModel.nearby(center.centerX, center.centerY, areas)
        assertEquals(listOf("ㄚ"), sure.map { it.label })
        val with = TouchModel.withAdjacent(sure, "ㄚ", areas)
        assertEquals("ㄚ", with.first().label)
        assertTrue(with.any { it.label == "ㄠ" && it.probability == 0f })
    }

    @Test
    fun aTouchOutsideTheCellStillLandsOnThatKey() {
        val keys = listOf(KeyArea("ㄚ", centerX = 50f, centerY = 50f, width = 100f, height = 100f))
        assertEquals("ㄚ", TouchModel.sense(50f, 50f, keys))
        val grown = 50f + 100f * TouchModel.SENSE_GROW * 0.5f
        assertEquals("ㄚ", TouchModel.sense(grown, 50f, keys))
        val past = 50f + 50f + 100f * TouchModel.SENSE_GROW + 5f
        assertNull(TouchModel.sense(past, 50f, keys))
    }

    @Test
    fun aTouchCloserToTheNeighbourLeavesTheRectangle() {
        val keys = listOf(
            KeyArea("ㄚ", centerX = 50f, centerY = 50f, width = 100f, height = 100f),
            KeyArea("ㄛ", centerX = 90f, centerY = 130f, width = 100f, height = 80f),
        )
        assertEquals("ㄚ", TouchModel.sense(50f, 50f, keys, owner = "ㄚ"))
        assertEquals("ㄛ", TouchModel.sense(92f, 96f, keys, owner = "ㄚ"))
    }

    @Test
    fun aPointInsideTheRectangleStaysThatKey() {
        val keys = listOf(
            KeyArea("ㄚ", centerX = 50f, centerY = 50f, width = 100f, height = 100f),
            KeyArea("ㄛ", centerX = 90f, centerY = 130f, width = 100f, height = 80f),
        )
        val sensed = TouchModel.sense(92f, 96f, keys, owner = "ㄚ")
        assertEquals("ㄛ", sensed)
        val pressed = keys[0]
        val left = pressed.centerX - pressed.width * 0.5f
        val top = pressed.centerY - pressed.height * 0.5f
        assertEquals(
            "ㄚ",
            heldLabel(
                left,
                top,
                left + pressed.width,
                top + pressed.height,
                pressed.label,
                92f,
                96f,
                sensed,
            ),
        )
    }
}

/** Portrait zhuyin centres in key-widths, with the same stagger as the soft keyboard. */
internal fun portraitZhuyinAreas(): List<KeyArea> {
    return ZhuyinLayout.rows.flatMapIndexed { rowIndex, row ->
        val leading = when (rowIndex) {
            1 -> 1f / 3f
            2 -> 2f / 3f
            else -> 0f
        }
        row.mapIndexed { index, key ->
            KeyArea(
                label = key.label,
                centerX = leading + index + 0.5f,
                centerY = rowIndex + 0.5f,
                width = 1f,
                height = 1f,
            )
        }
    }
}
