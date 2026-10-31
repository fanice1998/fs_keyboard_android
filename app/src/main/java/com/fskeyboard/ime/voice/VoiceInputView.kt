package com.fskeyboard.ime.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.fskeyboard.ime.R
import com.fskeyboard.ime.keyboard.KeyIcon
import com.fskeyboard.ime.keyboard.KeyIcons
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode
import kotlin.math.hypot

/**
 * Replaces the keys without changing their height. The microphone starts and
 * ends a session. The wide button cancels while listening, and returns to the
 * keys while idle.
 */
internal class VoiceInputView(context: Context) : FrameLayout(context) {
    var onMic: () -> Unit = {}
    var onButton: () -> Unit = {}

    var listening: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            button.contentDescription = context.getString(
                if (value) R.string.voice_cancel else R.string.voice_keyboard,
            )
            button.invalidate()
            invalidate()
        }

    var partial: String = ""
        set(value) {
            if (field == value) return
            field = value
            partialLayout = null
            invalidate()
        }

    var message: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val button = ButtonFace(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val partialPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var partialLayout: StaticLayout? = null
    private var partialWidth = 0
    private var micX = 0f
    private var micY = 0f
    private var micRadius = 0f

    init {
        setWillNotDraw(false)
        isClickable = true
        button.contentDescription = context.getString(R.string.voice_keyboard)
        button.setOnClickListener { onButton() }
        addView(button)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        button.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val buttonW = (width * 0.56f).toInt().coerceAtLeast(1)
        val buttonH = minOf(dp(44), (height * 0.30f).toInt()).coerceAtLeast(1)
        val margin = minOf(dp(12), (height * 0.06f).toInt())
        val l = (width - buttonW) / 2
        val t = (height - buttonH - margin).coerceAtLeast(0)
        button.layout(l, t, l + buttonW, (t + buttonH).coerceAtMost(height))
        placeMic()
    }

    override fun onDraw(canvas: Canvas) {
        val palette = KeyboardPalette.of(isNightMode())
        canvas.drawColor(palette.background)
        placeMic()
        if (message.isNotEmpty()) {
            messagePaint.color = palette.hint
            messagePaint.textSize = sp(14f)
            val baseline = dp(8) - messagePaint.ascent()
            canvas.drawText(message, width / 2f, baseline, messagePaint)
        }
        if (listening) drawWaves(canvas)
        fill.color = palette.key
        canvas.drawCircle(micX, micY, micRadius, fill)
        KeyIcons.draw(
            canvas,
            KeyIcon.MICROPHONE,
            RectF(micX - micRadius, micY - micRadius, micX + micRadius, micY + micRadius),
            palette.text,
            iconPaint,
            scale = 0.62f,
        )
        drawPartial(canvas, palette)
        if (listening && isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && nearMic(event.x, event.y)) onMic()
        return true
    }

    private fun placeMic() {
        val above = button.top.toFloat()
        val room = above.coerceAtLeast(0f)
        micRadius = minOf(dp(28).toFloat(), room * 0.28f).coerceAtLeast(dp(16).toFloat())
        micX = width / 2f
        micY = (room * 0.42f).coerceAtLeast(micRadius)
    }

    private fun drawWaves(canvas: Canvas) {
        wave.color = if (isNightMode()) Color.parseColor("#9BB6E0") else Color.parseColor("#5C7CBA")
        wave.strokeWidth = dp(2).toFloat()
        val phase = (SystemClock.uptimeMillis() % WAVE_MS) / WAVE_MS.toFloat()
        for (index in 0 until 3) {
            val t = (phase + index / 3f) % 1f
            wave.alpha = ((1f - t) * 96f).toInt().coerceIn(0, 96)
            canvas.drawCircle(micX, micY, micRadius * (1.2f + t * 1.15f), wave)
        }
    }

    private fun drawPartial(canvas: Canvas, palette: KeyboardPalette) {
        if (partial.isEmpty()) return
        val top = (micY + micRadius + dp(8)).toInt()
        val bottom = button.top - dp(8)
        val available = bottom - top
        if (available < dp(16)) return
        partialPaint.color = palette.text
        partialPaint.textSize = sp(16f)
        val layoutWidth = (width - dp(32)).coerceAtLeast(1)
        val layout = partialLayout?.takeIf { partialWidth == layoutWidth } ?: StaticLayout.Builder
            .obtain(partial, 0, partial.length, partialPaint, layoutWidth)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
            .also {
                partialLayout = it
                partialWidth = layoutWidth
            }
        canvas.save()
        canvas.translate(dp(16).toFloat(), top.toFloat())
        canvas.clipRect(0, 0, layoutWidth, available)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun nearMic(x: Float, y: Float): Boolean {
        return hypot(x - micX, y - micY) <= micRadius * 1.25f
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    private inner class ButtonFace(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val radius = dp(10).toFloat()
            if (listening) {
                paint.color = Color.parseColor("#7A3333")
                canvas.drawRoundRect(rect, radius, radius, paint)
                text.color = Color.parseColor("#F3E8E8")
                text.textSize = sp(16f)
                val label = context.getString(R.string.voice_cancel)
                val baseline = height / 2f - (text.ascent() + text.descent()) / 2f
                canvas.drawText(label, width / 2f, baseline, text)
            } else {
                paint.color = KeyboardPalette.of(isNightMode()).keyAction
                canvas.drawRoundRect(rect, radius, radius, paint)
                KeyIcons.draw(canvas, KeyIcon.KEYBOARD, rect, KeyboardPalette.of(isNightMode()).text, icon, scale = 0.46f)
            }
        }
    }

    private companion object {
        const val WAVE_MS = 1200L
    }
}
