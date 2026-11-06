package com.fskeyboard.ime.candidate

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import com.fskeyboard.ime.ui.dp
import kotlin.math.roundToInt

/**
 * A translucent reading bubble in its own window, so it can hang above the
 * keyboard on the candidate bar's top-left corner. The window stays
 * translucent; the bubble draws the rounded card. Touches pass through.
 */
internal class CompositionPreviewPopup(private val anchor: View) {
    private val bubble = Bubble(anchor.context)
    private val measure = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val window = PopupWindow(bubble).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
    }
    private var text: String = ""
    private var placedText: String = ""
    private var placedLeft = Int.MIN_VALUE
    private var placedTop = Int.MIN_VALUE
    private var placedWidth = Int.MIN_VALUE
    private var placedHeight = Int.MIN_VALUE
    private val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (text.isNotEmpty()) place()
    }
    private val onAttach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (text.isNotEmpty()) view.post { if (text.isNotEmpty()) place() }
        }

        override fun onViewDetachedFromWindow(view: View) {
            dismiss()
        }
    }

    /** Trackpad gliding fades the bubble with the candidate bar. */
    var faded: Boolean = false
        set(value) {
            field = value
            bubble.alpha = if (value) 0.35f else 1f
        }

    init {
        anchor.addOnLayoutChangeListener(onLayout)
        anchor.addOnAttachStateChangeListener(onAttach)
    }

    /** Shows [next], or hides the bubble when the composition is empty. */
    fun show(next: String) {
        if (next.isEmpty()) {
            hide()
            return
        }
        val same = next == text && window.isShowing
        text = next
        if (same) return
        if (!canShow()) return
        place()
    }

    /** Drops the window but keeps [text], so the next layout can put it back. */
    fun dismiss() {
        if (!window.isShowing) return
        try {
            window.dismiss()
        } catch (_: RuntimeException) {
            // The window token is already gone.
        }
        placedText = ""
    }

    fun hide() {
        text = ""
        dismiss()
    }

    fun release() {
        anchor.removeOnLayoutChangeListener(onLayout)
        anchor.removeOnAttachStateChangeListener(onAttach)
        hide()
    }

    /** A layout pass after the keyboard hides must not put the bubble back. */
    private fun canShow(): Boolean {
        return anchor.isAttachedToWindow &&
            anchor.windowToken != null &&
            anchor.windowVisibility == View.VISIBLE
    }

    private fun place() {
        val label = text
        if (label.isEmpty()) return
        if (!canShow()) return
        val root = anchor.rootView
        if (root.width <= 0) return
        val metrics = anchor.resources.displayMetrics
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics)
        measure.textSize = textSize
        val padX = anchor.dp(10)
        val padY = anchor.dp(4)
        val font = (measure.descent() - measure.ascent()).roundToInt().coerceAtLeast(1)
        val desiredWidth = measure.measureText(label).roundToInt() + padX * 2
        val desiredHeight = font + padY * 2
        val rootAt = IntArray(2)
        val anchorAt = IntArray(2)
        root.getLocationInWindow(rootAt)
        anchor.getLocationInWindow(anchorAt)
        val margin = anchor.dp(8)
        val frame = CompositionPreview.place(
            anchorLeft = anchorAt[0],
            anchorTop = anchorAt[1],
            bubbleWidth = desiredWidth,
            bubbleHeight = desiredHeight,
            boundsLeft = rootAt[0],
            boundsRight = rootAt[0] + root.width,
            margin = margin,
        )
        if (frame.width < 1 || frame.height < 1) return
        val samePlace = window.isShowing &&
            label == placedText &&
            frame.left == placedLeft &&
            frame.top == placedTop &&
            frame.width == placedWidth &&
            frame.height == placedHeight
        if (samePlace) return
        bubble.label = label
        bubble.textSizePx = textSize
        bubble.padX = padX.toFloat()
        bubble.cornerPx = anchor.dp(8).toFloat()
        bubble.invalidate()
        try {
            if (window.isShowing) {
                window.update(frame.left, frame.top, frame.width, frame.height)
            } else {
                window.width = frame.width
                window.height = frame.height
                window.showAtLocation(anchor, Gravity.NO_GRAVITY, frame.left, frame.top)
            }
            placedText = label
            placedLeft = frame.left
            placedTop = frame.top
            placedWidth = frame.width
            placedHeight = frame.height
        } catch (_: RuntimeException) {
            // The window token is gone when the keyboard closes on this frame.
        }
    }

    private class Bubble(context: android.content.Context) : View(context) {
        var label: String = ""
        var textSizePx: Float = 0f
        var padX: Float = 0f
        var cornerPx: Float = 0f

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CompositionPreview.TEXT
        }
        private val card = RectF()

        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (label.isEmpty() || width == 0 || height == 0) return
            card.set(0f, 0f, width.toFloat(), height.toFloat())
            fill.color = CompositionPreview.FILL
            canvas.drawRoundRect(card, cornerPx, cornerPx, fill)
            text.textSize = textSizePx
            val available = (width - padX * 2).coerceAtLeast(0f)
            val shown = TextUtils.ellipsize(label, text, available, TextUtils.TruncateAt.END)
            val baseline = height / 2f - (text.ascent() + text.descent()) / 2f
            canvas.drawText(shown, 0, shown.length, padX, baseline, text)
        }
    }
}
