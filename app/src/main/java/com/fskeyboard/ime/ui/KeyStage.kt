package com.fskeyboard.ime.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.fskeyboard.ime.keyboard.KeyboardView

/**
 * The keyboard decides the height. Overlays are measured to that exact height,
 * so a scrolling candidate list or the voice screen cannot stretch the window.
 * An invisible keyboard still counts. A gone one does not.
 */
internal class KeyStage(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var keyboard: View? = null
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is KeyboardView) keyboard = child
        }
        if (keyboard != null && keyboard.visibility != GONE) {
            keyboard.measure(widthSpec, unspecified)
        }
        val height = keyboard?.measuredHeight ?: 0
        val exact = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child === keyboard || child.visibility == GONE) continue
            child.measure(widthSpec, exact)
        }
        setMeasuredDimension(width, height.coerceAtLeast(0))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.layout(0, 0, width, height)
        }
    }
}
