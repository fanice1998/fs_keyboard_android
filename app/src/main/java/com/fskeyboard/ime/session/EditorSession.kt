package com.fskeyboard.ime.session

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.os.Bundle
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * The only type that writes the field or reads the caret.
 * Callers keep their own try/catch; these methods do not swallow failures.
 */
internal class EditorSession(
    private val connection: () -> InputConnection?,
) {
    fun hasField(): Boolean = connection() != null

    fun commitText(text: String) {
        connection()?.commitText(text, 1)
    }

    fun setComposing(text: String) {
        connection()?.setComposingText(text, 1)
    }

    fun finishComposing() {
        connection()?.finishComposingText()
    }

    /** Empty underline, both calls on one connection. */
    fun clearUnderline() {
        val ic = connection() ?: return
        ic.setComposingText("", 1)
        ic.finishComposingText()
    }

    fun deleteBefore(n: Int) {
        connection()?.deleteSurroundingText(n, 0)
    }

    /**
     * Deletes one unit before the caret.
     * A newline that [deleteSurroundingText] leaves in place is sent as
     * [KeyEvent.KEYCODE_DEL]. When neither call changes the text, later calls
     * at that same text do not call [deleteSurroundingText] again.
     * A field that cannot be read keeps the single surrounding delete.
     */
    fun deleteOne(): Boolean {
        val before = readBefore()
        if (before == null) {
            deleteBefore(1)
            stalledBefore = null
            return true
        }
        if (before.isEmpty() || before == stalledBefore) return false
        deleteBefore(1)
        val midway = readBefore()
        if (midway != null && midway != before) {
            stalledBefore = null
            return true
        }
        sendDel()
        val after = readBefore()
        val changed = after != null && after != before
        stalledBefore = if (changed) null else before
        return changed
    }

    /**
     * Deletes one unit after the caret.
     * Text that [deleteSurroundingText] leaves in place is sent as
     * [KeyEvent.KEYCODE_FORWARD_DEL]. When neither call changes the text, later calls
     * at that same text do not call [deleteSurroundingText] again.
     * A field that cannot be read keeps the single surrounding delete.
     * The stall is separate from [deleteOne].
     */
    fun deleteForward(): Boolean {
        val after = readAfter()
        if (after == null) {
            deleteAfter(1)
            stalledAfter = null
            return true
        }
        if (after.isEmpty() || after == stalledAfter) return false
        deleteAfter(1)
        val midway = readAfter()
        if (midway != null && midway != after) {
            stalledAfter = null
            return true
        }
        sendForwardDel()
        val later = readAfter()
        val changed = later != null && later != after
        stalledAfter = if (changed) null else after
        return changed
    }

    private var stalledBefore: String? = null
    private var stalledAfter: String? = null

    private fun readBefore(): String? {
        return try {
            textBefore(64)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun readAfter(): String? {
        return try {
            textAfter(64)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteAfter(n: Int) {
        connection()?.deleteSurroundingText(0, n)
    }

    private fun sendDel() {
        sendPlain(KeyEvent.KEYCODE_DEL)
    }

    private fun sendForwardDel() {
        sendPlain(KeyEvent.KEYCODE_FORWARD_DEL)
    }

    private fun sendPlain(keyCode: Int) {
        val ic = connection() ?: return
        val time = System.currentTimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(softKeyEvent(time, KeyEvent.ACTION_DOWN, keyCode, 0, flags))
        ic.sendKeyEvent(softKeyEvent(time, KeyEvent.ACTION_UP, keyCode, 0, flags))
    }

    fun selectedText(): CharSequence? = connection()?.getSelectedText(0)

    fun textBefore(n: Int): CharSequence? = connection()?.getTextBeforeCursor(n, 0)

    fun textAfter(n: Int): CharSequence? = connection()?.getTextAfterCursor(n, 0)

    fun extractedText(maxChars: Int): ExtractedText? {
        val ic = connection() ?: return null
        val request = ExtractedTextRequest().apply { hintMaxChars = maxChars }
        return ic.getExtractedText(request, 0)
    }

    fun setSelection(start: Int, end: Int): Boolean {
        return connection()?.setSelection(start, end) == true
    }

    /**
     * Navigation keys leave touch mode unless they are marked as a soft-keyboard event.
     * The editor then paints a gray focus wash over the field.
     */
    fun sendDownUp(keyCode: Int, meta: Int) {
        val ic = connection() ?: return
        ic.finishComposingText()
        val time = System.currentTimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(softKeyEvent(time, KeyEvent.ACTION_DOWN, keyCode, meta, flags))
        ic.sendKeyEvent(softKeyEvent(time, KeyEvent.ACTION_UP, keyCode, meta, flags))
    }

    /** Null when the field is gone. False when the editor ignored the action. */
    fun performAction(actionId: Int): Boolean? {
        val ic = connection() ?: return null
        return ic.performEditorAction(actionId)
    }

    fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
        val ic = connection() ?: return false
        return ic.commitContent(info, flags, opts)
    }

    inline fun batch(block: () -> Unit) {
        val ic = connection()
        try {
            try {
                ic?.beginBatchEdit()
            } catch (_: Exception) {
                // The field can close mid-edit. The caller still covers the commit.
            }
            block()
        } finally {
            try {
                ic?.endBatchEdit()
            } catch (_: Exception) {
                // The text is already in the field.
            }
        }
    }

    private fun softKeyEvent(time: Long, action: Int, keyCode: Int, meta: Int, flags: Int): KeyEvent {
        val deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD
        val event = KeyEvent(
            time,
            time,
            action,
            keyCode,
            0,
            meta,
            deviceId,
            0,
            flags,
        )
        rememberDispatched(
            event,
            DispatchedKey(
                action = action,
                keyCode = keyCode,
                meta = meta,
                deviceId = deviceId,
                flags = flags,
            ),
        )
        return event
    }
}

/** Arguments passed into a [KeyEvent]. The JVM android.jar drops them, and [KeyEvent.equals] throws. */
internal data class DispatchedKey(
    val action: Int,
    val keyCode: Int,
    val meta: Int,
    val deviceId: Int,
    val flags: Int,
)

private const val DISPATCHED_CAP = 32
private val dispatchedKeys = IdentityHashMap<KeyEvent, DispatchedKey>()
private val dispatchedOrder = ArrayDeque<KeyEvent>()
private val dispatchedLock = Any()

internal fun dispatchedKey(event: KeyEvent): DispatchedKey? {
    synchronized(dispatchedLock) {
        return dispatchedKeys[event]
    }
}

private fun rememberDispatched(event: KeyEvent, record: DispatchedKey) {
    synchronized(dispatchedLock) {
        if (dispatchedKeys.put(event, record) != null) return
        dispatchedOrder.addLast(event)
        while (dispatchedOrder.size > DISPATCHED_CAP) {
            dispatchedKeys.remove(dispatchedOrder.removeFirst())
        }
    }
}
