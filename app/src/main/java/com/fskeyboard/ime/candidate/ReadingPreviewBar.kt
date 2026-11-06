package com.fskeyboard.ime.candidate

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.fskeyboard.ime.ui.CompositionPreviewStyle
import com.fskeyboard.ime.ui.dp

/**
 * The zhuyin preview above the candidate row.
 * Blocks sit on the left and grow to the right, one Hanzi or one bopomofo symbol each.
 */
internal class ReadingPreviewBar(context: Context) : HorizontalScrollView(context) {
    private val row = LinearLayout(context)
    private val style = CompositionPreviewStyle.standard()
    private var shown: List<String> = emptyList()

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = OVER_SCROLL_NEVER
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        val pad = dp(8)
        row.setPadding(pad, dp(6), pad, dp(2))
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
        contentDescription = "注音輸入預覽"
    }

    fun render(blocks: List<String>) {
        if (blocks.isEmpty()) {
            if (shown.isNotEmpty()) {
                row.removeAllViews()
                shown = emptyList()
            }
            visibility = GONE
            return
        }
        visibility = VISIBLE
        if (blocks == shown) return
        shown = blocks
        row.removeAllViews()
        for (block in blocks) row.addView(chip(block))
        scrollTo(0, 0)
    }

    private fun chip(symbol: String): TextView {
        return TextView(context).apply {
            text = symbol
            setTextColor(style.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            minWidth = dp(28)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(style.block)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(4)
            }
        }
    }
}
