package com.fskeyboard.ime.keyboard

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterActionsTest {
    @Test
    fun noEnterActionIsAlwaysANewline() {
        val choice = EnterActions.choose(
            action = EditorInfo.IME_ACTION_SEARCH,
            noEnterAction = true,
            actionLabel = "搜尋",
            actionId = 9,
            multiline = false,
        )
        assertTrue(choice.newline)
    }

    @Test
    fun aCustomLabelIsShownAndUsesItsOwnId() {
        val choice = EnterActions.choose(
            action = EditorInfo.IME_ACTION_SEARCH,
            noEnterAction = false,
            actionLabel = " 送出 ",
            actionId = 42,
            multiline = true,
        )
        assertEquals("送出", choice.label)
        assertEquals(42, choice.actionId)
        assertFalse(choice.newline)
    }

    @Test
    fun namedActionsUseShortLabelsEvenOnSeveralLines() {
        assertEquals(
            "搜尋",
            EnterActions.choose(EditorInfo.IME_ACTION_SEARCH, false, null, 0, multiline = true).label,
        )
        assertEquals(
            "前往",
            EnterActions.choose(EditorInfo.IME_ACTION_GO, false, null, 0, false).label,
        )
        assertEquals(
            "傳送",
            EnterActions.choose(EditorInfo.IME_ACTION_SEND, false, null, 0, false).label,
        )
        assertEquals(
            "下一步",
            EnterActions.choose(EditorInfo.IME_ACTION_NEXT, false, null, 0, false).label,
        )
        assertEquals(
            "完成",
            EnterActions.choose(EditorInfo.IME_ACTION_DONE, false, null, 0, false).label,
        )
        assertEquals(
            "上一步",
            EnterActions.choose(EditorInfo.IME_ACTION_PREVIOUS, false, null, 0, false).label,
        )
    }

    @Test
    fun anUnspecifiedFieldBreaksLinesOrFinishes() {
        val lines = EnterActions.choose(EditorInfo.IME_ACTION_UNSPECIFIED, false, null, 0, multiline = true)
        assertTrue(lines.newline)
        val single = EnterActions.choose(EditorInfo.IME_ACTION_NONE, false, null, 0, multiline = false)
        assertEquals("完成", single.label)
        assertEquals(EditorInfo.IME_ACTION_DONE, single.actionId)
    }
}
