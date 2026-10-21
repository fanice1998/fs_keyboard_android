package com.fskeyboard.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import com.fskeyboard.ime.traced
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode

internal class KeyboardView(context: Context) : View(context) {
    var onKey: (KeyAction) -> Unit = {}

    /** After a touch-fired key is handled, with that touch's event time. Not for repeat or long press. */
    var onTyped: (eventUptimeMillis: Long) -> Unit = {}

    /** False while zhuyin is being composed: space then stays 平聲. */
    var trackpadAllowed: () -> Boolean = { false }

    /** Opens the cursor track. False keeps the gesture a space tap. */
    var onTrackpadStart: () -> Boolean = { false }

    /** Steps are graphemes right and lines down. Returns whether the caret moved. */
    var onCursorStep: (dx: Int, dy: Int) -> Boolean = { _, _ -> false }

    /** The keys are blank and the candidate bar should fade while this is true. */
    var onTrackpadActive: (Boolean) -> Unit = {}
    var rowHeightDp: Int = KeyboardHeight.DEFAULT_ROW_DP
        set(value) {
            val next = KeyboardHeight.clamp(value)
            if (field == next) return
            field = next
            requestLayout()
        }

    /** Label of the enter key for the field that is open. A newline keeps the return arrow. */
    var enterChoice: EnterChoice = EnterChoice()
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** Full-width Chinese punctuation on the symbol pages opened from zhuyin. */
    var fullWidthSymbols: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** Whether Latin keyboard top row keys have secondary numbers. */
    var latinTopNumberRow: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** Whether key presses produce haptic vibration feedback. */
    var vibrationEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            isHapticFeedbackEnabled = value
        }

    /** Whether pressing a character key shows a floating preview popup. */
    var keyPopupEnabled: Boolean = true

    private val keyPreview = KeyPreviewPopup(context)
    private val dismissPopup = Runnable {
        keyPreview.dismiss()
    }

    init {
        isHapticFeedbackEnabled = vibrationEnabled
    }

    private var mode = KeyboardMode.ZHUYIN
    private var base = KeyboardMode.ZHUYIN
    private val shiftLock = ShiftLock()
    private var rows = KeyboardLayouts.rows(
        mode,
        base = base,
        fullWidth = fullWidthSymbols,
        latinTopNumberRow = latinTopNumberRow,
    )
    private val boxes = ArrayList<KeyBox>()
    private var down: KeyBox? = null
    private var downPointer = NO_POINTER
    private var downX = 0f
    private var downY = 0f
    private var spaceCursor: SpaceCursor? = null
    private var trackpad = false
    private var zhuyinAreas: List<KeyArea> = emptyList()
    private var gap = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val secondaryLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
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
        release(dismissImmediately = true)
        buzz()
        fire(action)
    }

    /** A still finger on space becomes a trackpad, the way a hold does on iOS. */
    private val holdTrackpad = Runnable {
        val cursor = spaceCursor ?: return@Runnable
        if (cursor.tracking || down?.spec?.action != KeyAction.Space) return@Runnable
        cursor.hold()
        if (onTrackpadStart()) showTrackpad() else spaceCursor = null
    }

    /** [base] is the letter keyboard that the symbol pages return to. */
    fun setMode(mode: KeyboardMode, base: KeyboardMode = this.base) {
        if (this.mode == mode && this.base == base) return
        this.mode = mode
        this.base = base
        if (mode == KeyboardMode.ZHUYIN) shiftLock.releaseOnce()
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
            val pressed = !trackpad && (box == down || box.spec.highlighted)
            fill.color = when {
                trackpad -> palette.key
                pressed -> palette.keyPressed
                box.spec.role == KeyRole.ACTION || box.spec.role == KeyRole.MODIFIER -> palette.keyAction
                else -> palette.key
            }
            val draw = inset(box.rect)
            canvas.drawRoundRect(draw, radius, radius, fill)
            if (trackpad) continue
            val icon = box.spec.icon
            if (icon != null) {
                KeyIcons.draw(canvas, icon, draw, palette.text, iconPaint)
            } else if (box.spec.label.isNotEmpty()) {
                label.color = if (box.spec.role == KeyRole.SPACE) palette.hint else palette.text
                label.textSize = when {
                    box.spec.label.length >= 6 -> sp(11f)
                    box.spec.label.length >= 4 -> sp(13f)
                    box.spec.label.length > 1 -> sp(15f)
                    else -> sp(20f)
                }
                val baseline = draw.centerY() - (label.ascent() + label.descent()) / 2f
                canvas.drawText(box.spec.label, draw.centerX(), baseline, label)

                val secondary = box.spec.secondary
                if (secondary is KeyAction.Character) {
                    secondaryLabel.color = palette.hint
                    secondaryLabel.textSize = sp(10f)
                    val secX = draw.right - dp(4)
                    val secY = draw.top - secondaryLabel.ascent() + dp(2)
                    canvas.drawText(secondary.value.toString(), secX, secY, secondaryLabel)
                }
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
                if (moveSpace(event)) return true
                val box = down ?: return true
                val index = event.findPointerIndex(downPointer)
                if (index >= 0 && hit(event.getX(index), event.getY(index)) != box) {
                    release(dismissImmediately = true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) != downPointer) return true
                val box = down
                val released = hit(event.getX(index), event.getY(index))
                val tracking = trackpad
                release(dismissImmediately = false)
                if (!tracking && box != null && box == released && box.spec.action != KeyAction.Backspace) {
                    fire(box.spec.action, event.eventTime)
                }
                postDelayed(dismissPopup, 80L)
            }
            MotionEvent.ACTION_CANCEL -> release(dismissImmediately = true)
        }
        return true
    }

    private fun press(pointer: Int, x: Float, y: Float, eventTime: Long) {
        removeCallbacks(dismissPopup)
        down = hit(x, y)
        downPointer = pointer
        downX = x
        downY = y
        val spec = down?.spec
        invalidate()
        // Feedback on touch, not on release. The key itself still fires on release.
        // The vibration waits until this frame is submitted so it does not hold the highlight back.
        if (spec != null && vibrationEnabled) buzz()
        if (keyPopupEnabled && shouldShowKeyPreview(spec) && down != null) {
            keyPreview.show(this, spec!!.label, down!!.rect, isNightMode())
        } else {
            keyPreview.dismiss()
        }
        if (spec?.action == KeyAction.Space && trackpadAllowed()) {
            spaceCursor = SpaceCursor(slop = dp(10).toFloat(), xPerStep = dp(8).toFloat(), yPerStep = dp(22).toFloat())
            spaceCursor?.down(x, y)
            postDelayed(holdTrackpad, ViewConfiguration.getLongPressTimeout().toLong())
        } else if (spec?.action == KeyAction.Backspace) {
            fire(spec.action, eventTime)
            postDelayed(repeat, 320L)
        } else if (spec?.secondary != null) {
            postDelayed(longPress, 400L)
        }
    }

    /**
     * True once the space gesture owns the finger, including across the rest of the keyboard.
     * Leaving the space key before the slop still cancels, the same as any other key.
     */
    private fun moveSpace(event: MotionEvent): Boolean {
        val cursor = spaceCursor ?: return false
        val index = event.findPointerIndex(downPointer)
        if (index < 0) return false
        val wasTracking = cursor.tracking
        val steps = cursor.move(event.getX(index), event.getY(index)) ?: return false
        if (!wasTracking) {
            if (!onTrackpadStart()) {
                spaceCursor = null
                removeCallbacks(holdTrackpad)
                return false
            }
            showTrackpad()
        }
        val (sx, sy) = steps
        if ((sx != 0 || sy != 0) && onCursorStep(sx, sy)) {
            // A fast flick can cross several graphemes in one sample. One tick per sample
            // would add a binder call on top of setSelection and make that flick hesitate.
            if (kotlin.math.abs(sx) + kotlin.math.abs(sy) == 1) tick()
        }
        return true
    }

    private fun showTrackpad() {
        removeCallbacks(holdTrackpad)
        removeCallbacks(dismissPopup)
        keyPreview.dismiss()
        down = null
        trackpad = true
        onTrackpadActive(true)
        invalidate()
    }

    /** Commits the key still under a finger. Backspace already fired on its down. A trackpad does not. */
    private fun commitHeld(eventTime: Long) {
        if (trackpad) {
            release(dismissImmediately = true)
            return
        }
        val action = down?.spec?.action ?: return
        release(dismissImmediately = true)
        if (action != KeyAction.Backspace) fire(action, eventTime)
    }

    private fun release(dismissImmediately: Boolean = false) {
        stopRepeat()
        removeCallbacks(holdTrackpad)
        val wasTracking = trackpad
        spaceCursor = null
        trackpad = false
        down = null
        downPointer = NO_POINTER
        invalidate()
        if (wasTracking) onTrackpadActive(false)
        if (dismissImmediately) {
            removeCallbacks(dismissPopup)
            keyPreview.dismiss()
        }
    }

    /** One tick per move that actually crossed a character or a line. */
    private fun tick() {
        if (!vibrationEnabled) return
        val effect = if (Build.VERSION.SDK_INT >= 27) {
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        performHapticFeedback(effect)
    }

    /**
     * Vibrates after the pressed-key frame is submitted.
     * [performHapticFeedback] calls system_server and does not return until that call does,
     * so running it inside the touch handler holds the highlight back.
     */
    private fun buzz() {
        if (!vibrationEnabled) return
        val observer = viewTreeObserver
        if (!observer.isAlive) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            observer.registerFrameCommitCallback {
                if (vibrationEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            return
        }
        observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                post {
                    if (observer.isAlive) observer.removeOnDrawListener(this)
                    if (vibrationEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        })
    }

    /** A zhuyin key from a touch carries the keys that touch may have meant. */
    private fun fire(action: KeyAction, eventTime: Long? = null) {
        val typed = if (action is KeyAction.Bopomofo && eventTime != null) {
            action.copy(nearby = TouchModel.nearby(downX, downY, zhuyinAreas))
        } else {
            action
        }
        dispatch(typed, haptic = false)
        eventTime?.let(onTyped)
    }

    private fun dispatch(action: KeyAction, haptic: Boolean = true) {
        if (action == KeyAction.Shift) {
            shiftLock.onShift()
            rebuild()
            return
        }
        if (haptic && vibrationEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        traced("ime.dispatch") { onKey(action) }
        if (action is KeyAction.Character && shiftLock.onCharacter()) rebuild()
    }

    private fun rebuild() {
        dismissPopupImmediately()
        rows = KeyboardLayouts.rows(
            mode,
            shifted = shiftLock.uppercase,
            base = base,
            fullWidth = fullWidthSymbols,
            capsLock = shiftLock.mode == ShiftMode.LOCKED,
            enter = enterChoice,
            latinTopNumberRow = latinTopNumberRow,
        )
        requestLayout()
        if (width > 0 && height > 0) {
            layoutKeys(width, height)
        }
        invalidate()
    }

    fun dismissPopupImmediately() {
        removeCallbacks(dismissPopup)
        keyPreview.dismiss()
    }

    override fun onDetachedFromWindow() {
        dismissPopupImmediately()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            dismissPopupImmediately()
        }
    }

    private fun layoutKeys(width: Int, height: Int) {
        boxes.clear()
        zhuyinAreas = emptyList()
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
        zhuyinAreas = boxes.mapNotNull { box ->
            val action = box.spec.action as? KeyAction.Bopomofo ?: return@mapNotNull null
            KeyArea(action.label, box.rect.centerX(), box.rect.centerY(), box.rect.width(), box.rect.height())
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
