package com.fskeyboard.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
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
 * Floating preview overlay displayed above a pressed character key.
 * Renders directly inside the keyboard window's view hierarchy to avoid WindowManager/token issues.
 */
internal class KeyPreviewOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var label: String = ""
    private var isNight: Boolean = false
    private val cardRect = RectF()
    private var active = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
    }

    private val backgroundDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
    }

    private val dismissRunnable = Runnable {
        active = false
        visibility = GONE
        invalidate()
    }

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    fun show(text: String, keyRectInOverlay: RectF, night: Boolean) {
        removeCallbacks(dismissRunnable)
        label = text
        isNight = night
        active = true

        val palette = KeyboardPalette.of(night)
        val bgColor = if (night) Color.parseColor("#38383A") else Color.parseColor("#FFFFFF")
        val strokeColor = if (night) Color.parseColor("#505054") else Color.parseColor("#D1D5DB")
        backgroundDrawable.setColor(bgColor)
        backgroundDrawable.setStroke(dp(1), strokeColor)
        textPaint.color = palette.text

        textPaint.textSize = textSizeFor(text)
        val textWidth = textPaint.measureText(text)
        val minWidth = dp(48).toFloat()
        val popupWidth = maxOf(keyRectInOverlay.width() * 1.25f, textWidth + dp(24), minWidth)
        val popupHeight = dp(56).toFloat()

        val keyCenterX = keyRectInOverlay.centerX()
        val margin = dp(4).toFloat()
        val availableWidth = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val left = (keyCenterX - popupWidth / 2f).coerceIn(margin, availableWidth - popupWidth - margin)
        val top = (keyRectInOverlay.top - popupHeight - dp(6).toFloat()).coerceAtLeast(margin)
        cardRect.set(left, top, left + popupWidth, top + popupHeight)

        backgroundDrawable.setBounds(
            cardRect.left.toInt(),
            cardRect.top.toInt(),
            cardRect.right.toInt(),
            cardRect.bottom.toInt(),
        )

        visibility = VISIBLE
        invalidate()
    }

    fun dismissDelayed(delayMs: Long = 80L) {
        removeCallbacks(dismissRunnable)
        postDelayed(dismissRunnable, delayMs)
    }

    fun dismissImmediately() {
        removeCallbacks(dismissRunnable)
        active = false
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || label.isEmpty() || cardRect.isEmpty) return
        val corner = dp(10).toFloat()
        val shadowRect = RectF(cardRect.left, cardRect.top + dp(2), cardRect.right, cardRect.bottom + dp(3))
        canvas.drawRoundRect(shadowRect, corner, corner, shadowPaint)
        backgroundDrawable.draw(canvas)
        textPaint.textSize = textSizeFor(label)
        val baseline = cardRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, cardRect.centerX(), baseline, textPaint)
    }

    private fun textSizeFor(text: String): Float = when {
        text.length >= 4 -> sp(14f)
        text.length >= 3 -> sp(18f)
        text.length == 2 -> sp(22f)
        else -> sp(28f)
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
