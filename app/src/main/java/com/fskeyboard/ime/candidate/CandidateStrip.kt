package com.fskeyboard.ime.candidate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import com.fskeyboard.ime.R
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One scrolling row of candidates, drawn in a single pass.
 * A chip per [TextView] would be measured again on every key, and that measure
 * walks the whole keyboard before the frame can show the new row.
 * [HOST] is the strip itself; each chip is a virtual accessibility child.
 */
internal class CandidateStrip(context: Context) : View(context) {
    var onCandidate: (Candidate) -> Unit = {}

    /** Long-press on the clipboard chip. A tap still pastes. */
    var onClipboardHold: () -> Unit = {}

    init {
        isClickable = true
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private var candidates: List<Candidate> = emptyList()
    private var palette = KeyboardPalette.of(false)
    private val labels = ArrayList<String>()
    private val slots = ArrayList<RectF>()
    private var contentWidth = 0
    private var chipHeight = 0f
    private var corner = 0f
    private var pressed = NONE
    private var downX = 0f
    private var downY = 0f
    private var holdArmed = false
    private var holdConsumed = false
    private var accessFocus = HOST
    private val holdClipboard = Runnable {
        holdArmed = false
        val index = pressed
        if (candidates.getOrNull(index)?.source != CandidateSource.CLIPBOARD) return@Runnable
        holdConsumed = true
        pressed = NONE
        invalidate()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onClipboardHold()
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    fun show(next: List<Candidate>, nextPalette: KeyboardPalette) {
        val sameList = candidates == next
        val samePalette = palette == nextPalette
        if (sameList && samePalette) return
        palette = nextPalette
        if (!sameList) {
            candidates = next
            if (accessFocus >= candidates.size) accessFocus = HOST
            layoutChips()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            chipHeight.toInt().coerceAtLeast(1)
        } else {
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        }
        setMeasuredDimension(contentWidth, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (slots.isEmpty() || height == 0) return
        textPaint.textSize = sp(TEXT_SP)
        val radius = corner
        val stroke = 1.5f * resources.displayMetrics.density
        for (index in slots.indices) {
            val rect = chipRect(index)
            val clipboard = candidates.getOrNull(index)?.source == CandidateSource.CLIPBOARD
            fill.style = Paint.Style.FILL
            fill.color = when {
                index == pressed -> palette.keyPressed
                clipboard -> palette.background
                index == 0 -> palette.accent
                else -> palette.key
            }
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (clipboard) {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = stroke
                fill.color = palette.hint
                val inset = stroke / 2f
                canvas.drawRoundRect(
                    rect.left + inset,
                    rect.top + inset,
                    rect.right - inset,
                    rect.bottom - inset,
                    radius,
                    radius,
                    fill,
                )
                fill.style = Paint.Style.FILL
            }
            val baseline = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            if (clipboard) {
                drawClipboardLabel(canvas, rect, labels[index], baseline)
            } else {
                textPaint.color = palette.text
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(labels[index], rect.centerX(), baseline, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = hit(event.x, event.y)
                holdConsumed = false
                cancelHold()
                if (pressed != NONE) {
                    invalidate()
                    if (candidates.getOrNull(pressed)?.source == CandidateSource.CLIPBOARD) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        postDelayed(holdClipboard, ViewConfiguration.getLongPressTimeout().toLong())
                        holdArmed = true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed != NONE && movedPastSlop(event.x, event.y)) {
                    cancelHold()
                    pressed = NONE
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = pressed
                cancelHold()
                if (pressed != NONE) {
                    pressed = NONE
                    invalidate()
                }
                if (!holdConsumed && index != NONE && hit(event.x, event.y) == index) {
                    candidates.getOrNull(index)?.let(onCandidate)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                if (pressed != NONE) {
                    pressed = NONE
                    invalidate()
                }
            }
        }
        return true
    }

    private fun movedPastSlop(x: Float, y: Float): Boolean {
        return abs(x - downX) > slop || abs(y - downY) > slop
    }

    private fun cancelHold() {
        if (!holdArmed) return
        holdArmed = false
        removeCallbacks(holdClipboard)
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = nodes

    private fun layoutChips() {
        labels.clear()
        slots.clear()
        textPaint.textSize = sp(TEXT_SP)
        val padH = dp(12).toFloat()
        val margin = dp(4).toFloat()
        chipHeight = textPaint.descent() - textPaint.ascent() + dp(8)
        corner = dp(8).toFloat()
        var x = 0f
        for (candidate in candidates) {
            val label = CandidateChips.labelOf(candidate)
            labels += label
            val left = x + margin
            val right = left + chipContentWidth(candidate, label) + padH * 2f
            slots += RectF(left, 0f, right, chipHeight)
            x = right + margin
        }
        val width = ceil(x.toDouble()).toInt()
        if (width != contentWidth) {
            contentWidth = width
            requestLayout()
        }
    }

    /** The clipboard mark sits in front of the text, in the hint color. */
    private fun chipContentWidth(candidate: Candidate, label: String): Float {
        val body = textPaint.measureText(label)
        if (candidate.source != CandidateSource.CLIPBOARD) return body
        return textPaint.measureText(clipboardMark()) + dp(MARK_GAP_DP).toFloat() + body
    }

    private fun clipboardMark(): String = context.getString(R.string.clipboard_chip_mark)

    private fun drawClipboardLabel(canvas: Canvas, rect: RectF, label: String, baseline: Float) {
        val mark = clipboardMark()
        val gap = dp(MARK_GAP_DP).toFloat()
        val markWidth = textPaint.measureText(mark)
        val total = markWidth + gap + textPaint.measureText(label)
        var x = rect.centerX() - total / 2f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = palette.hint
        canvas.drawText(mark, x, baseline, textPaint)
        x += markWidth + gap
        textPaint.color = palette.text
        canvas.drawText(label, x, baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** Chip bounds centered in the row. [slots] store the horizontal span only. */
    private fun chipRect(index: Int): RectF {
        val slot = slots[index]
        val top = ((height - chipHeight) / 2f).coerceAtLeast(0f)
        return RectF(slot.left, top, slot.right, top + chipHeight)
    }

    private fun hit(x: Float, y: Float): Int {
        for (index in slots.indices) {
            if (chipRect(index).contains(x, y)) return index
        }
        return NONE
    }

    private fun hostNode(): AccessibilityNodeInfo {
        val host = newNode(HOST)
        onInitializeAccessibilityNodeInfo(host)
        host.className = CandidateStrip::class.java.name
        host.contentDescription = null
        for (index in candidates.indices) {
            host.addChild(this, index)
        }
        return host
    }

    private fun chipNode(index: Int): AccessibilityNodeInfo? {
        val candidate = candidates.getOrNull(index) ?: return null
        val info = newNode(index)
        info.className = Button::class.java.name
        info.packageName = context.packageName
        val clipboard = candidate.source == CandidateSource.CLIPBOARD
        info.text = labels.getOrNull(index) ?: candidate.text
        info.contentDescription = if (clipboard) {
            context.getString(R.string.clipboard_description, candidate.text)
        } else {
            candidate.text
        }
        info.isClickable = true
        info.isLongClickable = clipboard
        if (clipboard) info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        info.isEnabled = true
        info.isFocusable = true
        info.isVisibleToUser = true
        info.setParent(this)
        info.setSource(this, index)
        info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (accessFocus == index) {
            info.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            info.isAccessibilityFocused = true
        }
        val rect = chipRect(index)
        val bounds = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        @Suppress("DEPRECATION")
        info.setBoundsInParent(bounds)
        val screen = Rect(bounds)
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        screen.offset(origin[0], origin[1])
        info.setBoundsInScreen(screen)
        return info
    }

    private fun newNode(virtualId: Int): AccessibilityNodeInfo {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            AccessibilityNodeInfo(this, virtualId)
        } else {
            @Suppress("DEPRECATION")
            AccessibilityNodeInfo.obtain(this, virtualId)
        }
    }

    private fun sendVirtual(index: Int, eventType: Int) {
        val parent = parent ?: return
        val event = if (android.os.Build.VERSION.SDK_INT >= 30) {
            AccessibilityEvent(eventType)
        } else {
            @Suppress("DEPRECATION")
            AccessibilityEvent.obtain(eventType)
        }
        event.packageName = context.packageName
        event.className = Button::class.java.name
        event.contentDescription = candidates.getOrNull(index)?.text
        event.isEnabled = true
        event.setSource(this, index)
        parent.requestSendAccessibilityEvent(this, event)
    }

    private val nodes = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            return if (virtualViewId == HOST) hostNode() else chipNode(virtualViewId)
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    val candidate = candidates.getOrNull(virtualViewId) ?: return false
                    onCandidate(candidate)
                    return true
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    val candidate = candidates.getOrNull(virtualViewId) ?: return false
                    if (candidate.source != CandidateSource.CLIPBOARD) return false
                    onClipboardHold()
                    return true
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (virtualViewId !in candidates.indices) return false
                    if (accessFocus == virtualViewId) return true
                    val previous = accessFocus
                    accessFocus = virtualViewId
                    if (previous in candidates.indices) sendVirtual(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    sendVirtual(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessFocus != virtualViewId) return false
                    accessFocus = HOST
                    sendVirtual(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    return true
                }
            }
            return false
        }
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    private companion object {
        const val HOST = AccessibilityNodeProvider.HOST_VIEW_ID
        const val NONE = -2
        const val TEXT_SP = 18f
        const val MARK_GAP_DP = 6
    }
}
