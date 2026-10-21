package com.fskeyboard.ime.keyboard

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.PopupWindow
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp

/**
 * Determines whether a key should show the floating preview popup.
 *
 * Space, Enter, Backspace/Delete, Language switch (Globe/ABC/注音),
 * and Symbol keyboard switches (Symbols/123/#+=) are excluded.
 */
internal fun shouldShowKeyPreview(spec: KeySpec?): Boolean {
    val action = spec?.action ?: return false
    return when (action) {
        is KeyAction.Character -> true
        is KeyAction.Bopomofo -> true
        is KeyAction.Punctuation -> true
        else -> false
    }
}

/**
 * Floating preview popup displayed above a pressed character key.
 */
internal class KeyPreviewPopup(private val context: Context) {
    private val previewView = KeyPreviewView(context)
    private val popupWindow = PopupWindow(previewView).apply {
        isTouchable = false
        isFocusable = false
        isOutsideTouchable = false
        isClippingEnabled = false
        animationStyle = 0
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 23 && context !is Activity) {
            windowLayoutType = WindowManager.LayoutParams.TYPE_INPUT_METHOD
        }
        if (Build.VERSION.SDK_INT >= 21) {
            elevation = context.dp(8).toFloat()
        }
    }

    val isShowing: Boolean
        get() = popupWindow.isShowing

    fun show(parent: View, label: String, keyRect: RectF, isNight: Boolean) {
        if (!parent.isAttachedToWindow || parent.windowToken == null) return
        previewView.update(label, isNight)

        val location = IntArray(2)
        parent.getLocationOnScreen(location)

        val textWidth = previewView.measureTextWidth(label)
        val minWidth = parent.dp(48).toFloat()
        val popupWidth = maxOf(keyRect.width() * 1.25f, textWidth + parent.dp(24), minWidth).toInt()
        val popupHeight = parent.dp(56)

        val keyCenterX = location[0] + keyRect.centerX()
        val keyScreenTop = location[1] + keyRect.top

        val margin = parent.dp(4)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val popupX = (keyCenterX - popupWidth / 2f).toInt().coerceIn(margin, screenWidth - popupWidth - margin)
        val popupY = (keyScreenTop - popupHeight - parent.dp(6)).toInt().coerceAtLeast(margin)

        try {
            if (popupWindow.isShowing) {
                popupWindow.update(popupX, popupY, popupWidth, popupHeight)
            } else {
                popupWindow.width = popupWidth
                popupWindow.height = popupHeight
                popupWindow.showAtLocation(parent, Gravity.NO_GRAVITY, popupX, popupY)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show key preview popup", e)
        }
    }

    fun dismiss() {
        try {
            if (popupWindow.isShowing) {
                popupWindow.dismiss()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss key preview popup", e)
        }
    }

    private class KeyPreviewView(context: Context) : View(context) {
        private var label: String = ""
        private var isNight: Boolean = false

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        private val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
        }

        init {
            background = backgroundDrawable
            clipToOutline = true
            if (Build.VERSION.SDK_INT >= 21) {
                elevation = dp(8).toFloat()
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
        }

        fun update(text: String, night: Boolean) {
            label = text
            isNight = night
            val palette = KeyboardPalette.of(night)
            val bgColor = if (night) Color.parseColor("#38383A") else Color.parseColor("#FFFFFF")
            val strokeColor = if (night) Color.parseColor("#505054") else Color.parseColor("#D1D5DB")
            backgroundDrawable.setColor(bgColor)
            backgroundDrawable.setStroke(dp(1), strokeColor)
            textPaint.color = palette.text
            invalidate()
        }

        fun measureTextWidth(text: String): Float {
            textPaint.textSize = textSizeFor(text)
            return textPaint.measureText(text)
        }

        private fun textSizeFor(text: String): Float = when {
            text.length >= 4 -> sp(14f)
            text.length >= 3 -> sp(18f)
            text.length == 2 -> sp(22f)
            else -> sp(28f)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (label.isEmpty()) return
            textPaint.textSize = textSizeFor(label)
            val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, width / 2f, baseline, textPaint)
        }

        private fun sp(value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics,
        )
    }

    private companion object {
        const val TAG = "KeyPreviewPopup"
    }
}
