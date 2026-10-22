package com.fskeyboard.ime.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the enter key shows and does for one field.
 * A null [label] is the newline arrow. A label runs [actionId].
 */
internal data class EnterChoice(
    val label: String? = null,
    val actionId: Int = EditorInfo.IME_ACTION_NONE,
    /** Choosing a candidate. The key draws a check instead of the return arrow. */
    val confirming: Boolean = false,
) {
    val newline: Boolean
        get() = label == null && !confirming
}

/**
 * The field decides the key. [EditorInfo.IME_FLAG_NO_ENTER_ACTION] is a newline.
 * A custom [EditorInfo.actionLabel] is shown as written. A named action uses a short
 * label. With no action, a multi-line field breaks the line and a single-line field is 完成.
 */
internal object EnterActions {
    fun choose(info: EditorInfo?): EnterChoice {
        if (info == null) return EnterChoice()
        val input = info.inputType
        val text = input and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        val multiline = text && input and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        return choose(
            action = info.imeOptions and EditorInfo.IME_MASK_ACTION,
            noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0,
            actionLabel = info.actionLabel?.toString(),
            actionId = info.actionId,
            multiline = multiline,
        )
    }

    fun choose(
        action: Int,
        noEnterAction: Boolean,
        actionLabel: String?,
        actionId: Int,
        multiline: Boolean,
    ): EnterChoice {
        if (noEnterAction) return EnterChoice()
        val custom = actionLabel?.trim().orEmpty()
        if (custom.isNotEmpty()) return EnterChoice(label = custom, actionId = actionId)
        val named = when (action) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜尋"
            EditorInfo.IME_ACTION_SEND -> "傳送"
            EditorInfo.IME_ACTION_NEXT -> "下一步"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一步"
            else -> null
        }
        if (named != null) return EnterChoice(label = named, actionId = action)
        if (multiline) return EnterChoice()
        return EnterChoice(label = "完成", actionId = EditorInfo.IME_ACTION_DONE)
    }
}
