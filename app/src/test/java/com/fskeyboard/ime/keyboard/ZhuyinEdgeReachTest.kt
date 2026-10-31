package com.fskeyboard.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhuyinEdgeReachTest {
    @Test
    fun boAndEnnGrowIntoTheBlankBesideThem() {
        val bo = ZhuyinEdgeReach.adjust("ㄇ", left = 100f, right = 140f, blankLeft = 40f, blankRight = 12f)!!
        assertEquals(100f - 40f * ZhuyinEdgeReach.HIT, bo.hitLeft, 0.01f)
        assertEquals(140f, bo.hitRight, 0.01f)
        assertEquals(100f - 40f * ZhuyinEdgeReach.DRAW, bo.drawLeft, 0.01f)
        assertEquals(140f, bo.drawRight, 0.01f)

        val enn = ZhuyinEdgeReach.adjust("ㄣ", left = 100f, right = 140f, blankLeft = 12f, blankRight = 40f)!!
        assertEquals(100f, enn.hitLeft, 0.01f)
        assertEquals(140f + 40f * ZhuyinEdgeReach.HIT, enn.hitRight, 0.01f)
        assertEquals(100f, enn.drawLeft, 0.01f)
        assertEquals(140f + 40f * ZhuyinEdgeReach.DRAW, enn.drawRight, 0.01f)
    }

    @Test
    fun otherKeysAndAMissingBlankStayPut() {
        assertNull(ZhuyinEdgeReach.adjust("ㄅ", 0f, 40f, 30f, 30f))
        assertNull(ZhuyinEdgeReach.adjust("ㄇ", 20f, 60f, blankLeft = 0f, blankRight = 30f))
        assertNull(ZhuyinEdgeReach.adjust("ㄣ", 20f, 60f, blankLeft = 30f, blankRight = 0f))
        assertNull(ZhuyinEdgeReach.adjust("ㄇ", 40f, 40f, blankLeft = 10f, blankRight = 10f))
    }
}
