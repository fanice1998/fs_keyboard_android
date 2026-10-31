package com.fskeyboard.ime.candidate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.fskeyboard.ime.R
import com.fskeyboard.ime.keyboard.KeyIcon
import com.fskeyboard.ime.keyboard.KeyIcons
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode

/** Up, down, and delete for one candidate. A tap outside closes it. */
internal object CandidateWeightMenu {
    fun show(anchor: View, onChoose: (CandidateWeight) -> Unit) {
        if (anchor.windowToken == null) return
        val context = anchor.context
        val palette = KeyboardPalette.of(anchor.isNightMode())
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            background = CandidateChips.rounded(anchor, palette.key)
        }
        val popup = PopupWindow(row, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.elevation = context.dp(6).toFloat()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        fun button(icon: KeyIcon, description: Int, weight: CandidateWeight) {
            val view = IconButton(anchor, icon, context.getString(description), palette.text)
            view.setOnClickListener {
                popup.dismiss()
                onChoose(weight)
            }
            row.addView(view, LinearLayout.LayoutParams(context.dp(48), context.dp(44)))
        }
        button(KeyIcon.UP, R.string.candidate_promote, CandidateWeight.PROMOTE)
        button(KeyIcon.DOWN, R.string.candidate_demote, CandidateWeight.DEMOTE)
        button(KeyIcon.TRASH, R.string.candidate_forget, CandidateWeight.HIDE)
        try {
            popup.showAsDropDown(anchor)
        } catch (_: Exception) {
            popup.dismiss()
        }
    }

    private class IconButton(
        anchor: View,
        private val icon: KeyIcon,
        description: String,
        private val color: Int,
    ) : View(anchor.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            contentDescription = description
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            KeyIcons.draw(
                canvas,
                icon,
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                color,
                paint,
            )
        }
    }
}
