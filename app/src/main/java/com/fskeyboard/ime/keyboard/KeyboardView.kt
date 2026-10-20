package com.fskeyboard.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.fskeyboard.ime.traced
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode

internal class KeyboardView(context: Context) : View(context) {
    var onKey: (KeyAction) -> Unit = {}

    /** After a touch-fired key is handled, with that touch's event time. Not for repeat or long press. */
    var onTyped: (eventUptimeMillis: Long) -> Unit = {}
    var rowHeightDp: Int = KeyboardHeight.DEFAULT_ROW_DP
        set(value) {
            val next = KeyboardHeight.clamp(value)
            if (field == next) return
            field = next
            requestLayout()
        }

    private var mode = KeyboardMode.ZHUYIN
    private var base = KeyboardMode.ZHUYIN
    private var shifted = false
    private var rows = KeyboardLayouts.rows(mode, shifted, base)
    private val boxes = ArrayList<KeyBox>()
    private var down: KeyBox? = null
    private var downPointer = NO_POINTER
    private var gap = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val repeat = object : Runnable {
        override fun run() {
            val spec = down?.spec ?: return
            if (spec.action != KeyAction.Backspace) return
            dispatch(spec.action)
            postDelayed(this, 45L)
        }
    }

    private val longPress = Runnable {
        val action = down?.spec?.secondary ?: return@Runnable
        release()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        fire(action)
    }

    /** [base] is the letter keyboard that the symbol pages return to. */
    fun setMode(mode: KeyboardMode, base: KeyboardMode = this.base) {
        if (this.mode == mode && this.base == base) return
        this.mode = mode
        this.base = base
        shifted = false
        rebuild()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = rows.size * dp(rowHeightDp) + dp(6)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val palette = KeyboardPalette.of(isNightMode())
        canvas.drawColor(palette.background)
        val radius = dp(11).toFloat()
        for (box in boxes) {
            val pressed = box == down || box.spec.highlighted
            fill.color = when {
                pressed -> palette.keyPressed
                box.spec.role == KeyRole.ACTION || box.spec.role == KeyRole.MODIFIER -> palette.keyAction
                else -> palette.key
            }
            val draw = inset(box.rect)
            canvas.drawRoundRect(draw, radius, radius, fill)
            val icon = box.spec.icon
            if (icon != null) {
                KeyIcons.draw(canvas, icon, draw, palette.text, iconPaint)
            } else if (box.spec.label.isNotEmpty()) {
                label.color = if (box.spec.role == KeyRole.SPACE) palette.hint else palette.text
                label.textSize = if (box.spec.label.length > 1) sp(15f) else sp(20f)
                val baseline = draw.centerY() - (label.ascent() + label.descent()) / 2f
                canvas.drawText(box.spec.label, draw.centerX(), baseline, label)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A finger landing before the held one lifts commits the held key first,
                // so fast two-thumb rollover keeps both keys in order.
                commitHeld(event.eventTime)
                val index = event.actionIndex
                press(event.getPointerId(index), event.getX(index), event.getY(index), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                val box = down ?: return true
                val index = event.findPointerIndex(downPointer)
                if (index >= 0 && hit(event.getX(index), event.getY(index)) != box) {
                    release()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) != downPointer) return true
                val box = down
                val released = hit(event.getX(index), event.getY(index))
                release()
                if (box != null && box == released && box.spec.action != KeyAction.Backspace) {
                    fire(box.spec.action, event.eventTime)
                }
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun press(pointer: Int, x: Float, y: Float, eventTime: Long) {
        down = hit(x, y)
        downPointer = pointer
        val spec = down?.spec
        invalidate()
        // Feedback on touch, not on release. The key itself still fires on release.
        if (spec != null) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (spec?.action == KeyAction.Backspace) {
            fire(spec.action, eventTime)
            postDelayed(repeat, 320L)
        } else if (spec?.secondary != null) {
            postDelayed(longPress, 400L)
        }
    }

    /** Commits the key still under a finger. Backspace already fired on its down. */
    private fun commitHeld(eventTime: Long) {
        val action = down?.spec?.action ?: return
        release()
        if (action != KeyAction.Backspace) fire(action, eventTime)
    }

    private fun release() {
        stopRepeat()
        down = null
        downPointer = NO_POINTER
        invalidate()
    }

    private fun fire(action: KeyAction, eventTime: Long? = null) {
        dispatch(action, haptic = false)
        eventTime?.let(onTyped)
    }

    private fun dispatch(action: KeyAction, haptic: Boolean = true) {
        if (action == KeyAction.Shift) {
            shifted = !shifted
            rebuild()
            return
        }
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        traced("ime.dispatch") { onKey(action) }
        if (action is KeyAction.Character && shifted && mode == KeyboardMode.LATIN) {
            shifted = false
            rebuild()
        }
    }

    private fun rebuild() {
        rows = KeyboardLayouts.rows(mode, shifted, base)
        requestLayout()
        if (width > 0 && height > 0) {
            layoutKeys(width, height)
        }
        invalidate()
    }

    private fun layoutKeys(width: Int, height: Int) {
        boxes.clear()
        if (rows.isEmpty() || width == 0 || height == 0) return
        gap = dp(6).toFloat()
        val pad = dp(3).toFloat()
        val available = width - 2f * pad
        val rowHeight = height.toFloat() / rows.size
        var top = 0f
        for (row in rows) {
            val units = row.keys.sumOf { it.weight.toDouble() }.toFloat() + row.leading + row.trailing
            val unit = available / units
            var left = pad + row.leading * unit
            for (key in row.keys) {
                val cellWidth = key.weight * unit
                boxes += KeyBox(key, RectF(left, top, left + cellWidth, top + rowHeight))
                left += cellWidth
            }
            top += rowHeight
        }
    }

    private fun inset(rect: RectF): RectF {
        val gutter = gap / 2f
        return RectF(
            rect.left + gutter,
            rect.top + gutter,
            rect.right - gutter,
            rect.bottom - gutter,
        )
    }

    private fun hit(x: Float, y: Float): KeyBox? {
        return boxes.firstOrNull { it.rect.contains(x, y) }
    }

    private fun stopRepeat() {
        removeCallbacks(repeat)
        removeCallbacks(longPress)
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics,
        )
    }

    private data class KeyBox(val spec: KeySpec, val rect: RectF)

    private companion object {
        const val NO_POINTER = -1
    }
}
