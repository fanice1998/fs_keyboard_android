package com.fskeyboard.ime.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A short-lived bubble in its own window, so a top-row key can float over the candidate bar
 * and the app above the keyboard. The window is a child of the keyboard, so its position
 * uses [View.getLocationInWindow]. The window itself stays translucent; the bubble draws
 * the rounded card and its shadow, which a rectangular window background would clip.
 */
internal class KeyPreviewPopup(private val host: View) {
    private val bubble = Bubble(host.context)
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val window = PopupWindow(bubble).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
    }
    private val hideRunnable = Runnable { hide() }

    val isShowing: Boolean
        get() = window.isShowing

    fun show(label: String, key: RectF, boardWidth: Int) {
        if (!host.isAttachedToWindow || host.windowToken == null || boardWidth <= 0) return
        if (label.isEmpty()) return
        host.removeCallbacks(hideRunnable)
        val textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            KeyPreview.textSizeSp(label),
            host.resources.displayMetrics,
        )
        measurePaint.textSize = textSize
        val desired = max(
            host.dp(52).toFloat(),
            max(key.width(), measurePaint.measureText(label) + host.dp(24)),
        )
        val frame = KeyPreview.place(
            keyLeft = key.left,
            keyTop = key.top,
            keyRight = key.right,
            boardWidth = boardWidth.toFloat(),
            popupWidth = desired,
            popupHeight = host.dp(64).toFloat(),
            margin = host.dp(4).toFloat(),
            gap = host.dp(6).toFloat(),
        )
        if (frame.width < 1f || frame.height < 1f) return
        val shadow = host.dp(10)
        val origin = IntArray(2)
        host.getLocationInWindow(origin)
        val x = origin[0] + frame.left.roundToInt() - shadow
        val y = origin[1] + frame.top.roundToInt() - shadow
        val width = frame.width.roundToInt() + shadow * 2
        val height = frame.height.roundToInt() + shadow * 2
        bubble.label = label
        bubble.textSizePx = textSize
        bubble.shadowPx = shadow.toFloat()
        bubble.cornerPx = host.dp(16).toFloat()
        bubble.invalidate()
        try {
            if (window.isShowing) {
                window.update(x, y, width, height)
            } else {
                window.width = width
                window.height = height
                window.showAtLocation(host, Gravity.NO_GRAVITY, x, y)
            }
        } catch (error: RuntimeException) {
            // The window token is gone when the keyboard closes on this frame.
        }
    }

    fun cancelDismiss() {
        host.removeCallbacks(hideRunnable)
    }

    fun hideLater(delayMillis: Long) {
        host.removeCallbacks(hideRunnable)
        if (!window.isShowing) return
        host.postDelayed(hideRunnable, delayMillis)
    }

    fun hide() {
        host.removeCallbacks(hideRunnable)
        if (!window.isShowing) return
        try {
            window.dismiss()
        } catch (error: RuntimeException) {
            // Already detached from the window manager.
        }
    }

    private class Bubble(context: android.content.Context) : View(context) {
        var label: String = ""
        var textSizePx: Float = 0f
        var shadowPx: Float = 0f
        var cornerPx: Float = 0f

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private val card = RectF()

        init {
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (label.isEmpty() || width == 0 || height == 0) return
            val palette = KeyboardPalette.of(isNightMode())
            val inset = shadowPx
            card.set(inset, inset, width - inset, height - inset)
            if (card.width() <= 0f || card.height() <= 0f) return
            fill.color = palette.keyPreview
            fill.setShadowLayer(inset * 0.55f, 0f, inset * 0.22f, 0x44000000)
            canvas.drawRoundRect(card, cornerPx, cornerPx, fill)
            fill.clearShadowLayer()
            text.color = palette.text
            text.textSize = textSizePx
            val baseline = card.centerY() - (text.ascent() + text.descent()) / 2f
            canvas.drawText(label, card.centerX(), baseline, text)
        }
    }
}
