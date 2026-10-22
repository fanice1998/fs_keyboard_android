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

    /** Locks a modifier after a long press, or unlocks it when its key is tapped again. */
    var onModifierHold: (ComputerKey, Boolean) -> Unit = { _, _ -> }

    /** After a touch-fired key is handled, with that touch's event time. Not for repeat or long press. */
    var onTyped: (eventUptimeMillis: Long) -> Unit = {}

    /** False when a space glide should stay a tap, for example while the keyboard is closed. */
    var trackpadAllowed: () -> Boolean = { false }

    /** Opens the cursor track. False keeps the gesture a space tap. */
    var onTrackpadStart: () -> Boolean = { false }

    /**
     * Pixels from where the glide engaged, right and down.
     * Returns whether the snapped caret moved.
     */
    var onCursorGlide: (dxPx: Float, dyPx: Float) -> Boolean = { _, _ -> false }

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

    /** Whether a swipe on an English letter sends the computer key assigned to that direction. */
    var computerKeys: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** Assignments for the eight swipe directions. Used when [computerKeys] is on. */
    var computerMap: ComputerKeyMap = ComputerKeyMap.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /**
     * When set, a tap on a letter, digit, comma, period, or delete reports that key and does not type.
     * Swipes, long-press, and the space trackpad stay off.
     */
    var onLetterPick: ((Char) -> Unit)? = null

    /** Letter drawn pressed, so the settings editor can show which key is being edited. */
    var markedLetter: Char? = null
        set(value) {
            val next = value?.lowercaseChar()
            if (field == next) return
            field = next
            invalidate()
        }

    /** One-shot and locked modifiers. Their labels stay highlighted. */
    var armedComputerKeys: Set<ComputerKey> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Modifiers that stay down after the finger lifts, until their key is tapped. */
    var lockedComputerKeys: Set<ComputerKey> = emptySet()

    /** Whether key presses produce haptic vibration feedback. */
    var vibrationEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            isHapticFeedbackEnabled = value
        }

    /** Whether a press floats that key's label above the key. */
    var keyPreviewEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) endPreview(linger = false)
        }

    init {
        isHapticFeedbackEnabled = vibrationEnabled
    }

    private var mode = KeyboardMode.ZHUYIN
    private var base = KeyboardMode.ZHUYIN
    private var numberPad = NumberPad.NONE
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
    private val keyPreview = KeyPreviewPopup(this)
    private var previewBox: KeyBox? = null
    private var previewLinger = false
    private var previewFramePosted = false
    private var spaceCursor: SpaceCursor? = null
    private var swipe: KeySwipe? = null
    private var swipeDirection: SwipeDirection? = null
    private var backspaceSent = false

    /** The action the hold timer is already sending, so key-up does not send it once more. */
    private var repeating: KeyAction? = null
    private val modifierHolds = ArrayList<Pair<Int, ComputerKey>>()
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
    private val swipeLabel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val repeat = object : Runnable {
        override fun run() {
            val action = currentRepeat() ?: return
            repeating = action
            if (action == KeyAction.Backspace) backspaceSent = true
            dispatch(action)
            val interval = KeyRepeat.interval(action) ?: return
            postDelayed(this, interval)
        }
    }

    private val longPress = Runnable {
        val spec = down?.spec ?: return@Runnable
        val hold = spec.holdKey
        if (hold != null && swipeDirection == null) {
            beginHold(hold)
            return@Runnable
        }
        val action = spec.secondary ?: return@Runnable
        release()
        buzz()
        fire(action)
    }

    /** A modifier swipe becomes a hold when the finger stays in that direction. */
    private val holdSwipe = Runnable {
        val direction = swipeDirection ?: return@Runnable
        val action = down?.spec?.swipeAction(direction) ?: return@Runnable
        val key = (action as? KeyAction.Computer)?.key ?: return@Runnable
        if (!key.modifier) return@Runnable
        beginHold(key)
    }

    /** A still finger on space becomes a trackpad, the way a hold does on iOS. */
    private val holdTrackpad = Runnable {
        val cursor = spaceCursor ?: return@Runnable
        if (cursor.tracking || down?.spec?.action != KeyAction.Space) return@Runnable
        cursor.hold()
        if (onTrackpadStart()) showTrackpad() else spaceCursor = null
    }

    /** [base] is the letter keyboard that the symbol pages return to. */
    fun setMode(mode: KeyboardMode, base: KeyboardMode = this.base, numberPad: NumberPad = NumberPad.NONE) {
        val pad = if (mode == KeyboardMode.NUMBER) numberPad else NumberPad.NONE
        if (this.mode == mode && this.base == base && this.numberPad == pad) return
        this.mode = mode
        this.base = base
        this.numberPad = pad
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
        endPreview(linger = false)
    }

    override fun onDetachedFromWindow() {
        releaseAllHolds()
        stopRepeat()
        previewFramePosted = false
        endPreview(linger = false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val palette = KeyboardPalette.of(isNightMode())
        canvas.drawColor(palette.background)
        val radius = dp(11).toFloat()
        for (box in boxes) {
            val marked = markedLetter != null && swipeKeyToken(box.spec) == markedLetter
            val faceKey = (box.spec.action as? KeyAction.Computer)?.key
            val faceArmed = faceKey != null && faceKey in armedComputerKeys
            val lockHeld = box.spec.holdKey in lockedComputerKeys ||
                box.spec.swipes.values.any { it in lockedComputerKeys }
            val pressed = !trackpad && (box == down || box.spec.highlighted || marked || faceArmed || lockHeld)
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
                val hasSwipes = box.spec.swipes.isNotEmpty() || box.spec.extraSwipes.isNotEmpty()
                label.textSize = when {
                    box.spec.label.length >= 6 -> sp(11f)
                    box.spec.label.length >= 4 -> sp(13f)
                    box.spec.label.length > 1 -> sp(15f)
                    hasSwipes -> sp(16f)
                    else -> sp(20f)
                }
                val baseline = draw.centerY() - (label.ascent() + label.descent()) / 2f
                canvas.drawText(box.spec.label, draw.centerX(), baseline, label)

                val secondary = box.spec.secondary
                if (secondary is KeyAction.Character) {
                    secondaryLabel.textAlign = Paint.Align.RIGHT
                    secondaryLabel.color = palette.hint
                    secondaryLabel.textSize = sp(10f)
                    val secX = draw.right - dp(4)
                    val secY = draw.top - secondaryLabel.ascent() + dp(2)
                    canvas.drawText(secondary.value.toString(), secX, secY, secondaryLabel)
                }
            }
            drawSwipeLabels(canvas, box, draw, palette)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // A finger landing before the held one lifts commits the held key first,
                // so fast two-thumb rollover keeps both keys in order.
                commitHeld(event.eventTime)
                val index = event.actionIndex
                press(event.getPointerId(index), event.getX(index), event.getY(index), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (moveSpace(event)) return true
                if (moveSwipe(event)) return true
                val box = down ?: return true
                val index = event.findPointerIndex(downPointer)
                if (index >= 0 && hit(event.getX(index), event.getY(index)) != box) {
                    release()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val index = event.actionIndex
                val pointer = event.getPointerId(index)
                if (modifierHolds.any { it.first == pointer }) {
                    releaseHold(pointer)
                    if (pointer == downPointer) release()
                    return true
                }
                if (pointer != downPointer) return true
                if (onLetterPick != null) {
                    val box = down
                    val token = box?.spec?.let { swipeKeyToken(it) }
                    val stillOnKey = box != null && box == hit(event.getX(index), event.getY(index))
                    release()
                    if (stillOnKey && token != null) onLetterPick?.invoke(token)
                    return true
                }
                val box = down
                val released = hit(event.getX(index), event.getY(index))
                val tracking = trackpad
                val direction = swipe?.move(
                    event.getX(index) - downX,
                    event.getY(index) - downY,
                )
                val tapped = box != null && !tracking && direction == null && box == released
                val unlocked = tapped && unlockLockedModifiers(box.spec)
                val action = when {
                    box == null || tracking || unlocked -> null
                    direction != null -> fresh(box.spec.swipeAction(direction))
                    box != released -> null
                    else -> fresh(box.spec.action)
                }
                val linger = action != null && box != null &&
                    (direction != null || (box == released && KeyPreview.visible(box.spec)))
                release(lingerPreview = linger)
                if (action != null) fire(action, event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                release()
                releaseAllHolds()
            }
        }
        return true
    }

    private fun press(pointer: Int, x: Float, y: Float, eventTime: Long) {
        down = hit(x, y)
        downPointer = pointer
        downX = x
        downY = y
        val spec = down?.spec
        invalidate()
        if (onLetterPick != null) return
        // Feedback on touch, not on release. The key itself still fires on release.
        // The vibration and the floating label wait until this frame is submitted,
        // so a window call does not hold the highlight back.
        if (spec != null && vibrationEnabled) buzz()
        armPreview(down)
        val hasSwipes = spec != null && (spec.swipes.isNotEmpty() || spec.extraSwipes.isNotEmpty())
        swipe = if (hasSwipes) KeySwipe(slop = dp(22).toFloat()) else null
        swipeDirection = null
        backspaceSent = false
        repeating = null
        when {
            spec?.action == KeyAction.Space && trackpadAllowed() -> {
                spaceCursor = SpaceCursor(slop = dp(10).toFloat())
                spaceCursor?.down(x, y)
                postDelayed(holdTrackpad, ViewConfiguration.getLongPressTimeout().toLong())
            }
            spec?.action == KeyAction.Backspace && !hasSwipes -> {
                backspaceSent = true
                fire(spec.action, eventTime)
                postDelayed(repeat, KeyRepeat.DELAY_MS)
            }
            spec?.action == KeyAction.Backspace -> postDelayed(repeat, KeyRepeat.DELAY_MS)
            spec?.holdKey != null || spec?.secondary != null -> postDelayed(longPress, 400L)
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
        val travel = cursor.move(event.getX(index), event.getY(index)) ?: return false
        if (!wasTracking) {
            if (!onTrackpadStart()) {
                spaceCursor = null
                removeCallbacks(holdTrackpad)
                return false
            }
            showTrackpad()
        }
        val (dx, dy) = travel
        if ((dx != 0f || dy != 0f) && onCursorGlide(dx, dy)) tick()
        return true
    }

    private fun showTrackpad() {
        removeCallbacks(holdTrackpad)
        down = null
        trackpad = true
        swipe = null
        swipeDirection = null
        endPreview(linger = false)
        onTrackpadActive(true)
        invalidate()
    }

    /**
     * True while this finger is choosing a computer key.
     * The direction is measured from the touch-down point, including after the finger
     * has left the key. A quick slide otherwise crosses the key edge before the slop
     * and the press is cancelled. Releasing outside the key before a direction is
     * chosen still types nothing. A direction with no assignment types nothing.
     */
    private fun moveSwipe(event: MotionEvent): Boolean {
        val gesture = swipe ?: return false
        val index = event.findPointerIndex(downPointer)
        if (index < 0) return false
        val direction = gesture.move(event.getX(index) - downX, event.getY(index) - downY)
        if (direction != swipeDirection) {
            swipeDirection = direction
            repeating = null
            releaseHold(downPointer)
            if (direction != null) {
                removeCallbacks(longPress)
                removeCallbacks(repeat)
                scheduleHoldSwipe(direction)
                scheduleArrowRepeat(direction)
            } else {
                removeCallbacks(holdSwipe)
                val spec = down?.spec
                if (spec?.action == KeyAction.Backspace) postDelayed(repeat, KeyRepeat.DELAY_MS)
                if (spec?.holdKey != null || spec?.secondary != null) {
                    removeCallbacks(longPress)
                    postDelayed(longPress, 400L)
                }
            }
            updateSwipePreview(direction)
            invalidate()
        }
        return true
    }

    /** An arrow starts repeating once the finger has stayed in that direction. */
    private fun scheduleArrowRepeat(direction: SwipeDirection) {
        val action = down?.spec?.swipeAction(direction) ?: return
        if (action !is KeyAction.Computer || !action.key.isArrow) return
        postDelayed(repeat, KeyRepeat.DELAY_MS)
    }

    /** Backspace with no direction, or the arrow whose direction the finger is holding. */
    private fun currentRepeat(): KeyAction? {
        val spec = down?.spec ?: return null
        val direction = swipeDirection
        val action = if (direction == null) spec.action else spec.swipeAction(direction)
        if (action == null || KeyRepeat.interval(action) == null) return null
        if (direction != null && action == KeyAction.Backspace) return null
        return action
    }

    /** Drops a key the hold timer already delivered, so release does not send it again. */
    private fun fresh(action: KeyAction?): KeyAction? {
        if (action == null || KeyRepeat.alreadyDelivered(action, repeating, backspaceSent)) return null
        return action
    }

    private fun scheduleHoldSwipe(direction: SwipeDirection) {
        removeCallbacks(holdSwipe)
        val key = (down?.spec?.swipeAction(direction) as? KeyAction.Computer)?.key
        if (key != null && key.modifier) postDelayed(holdSwipe, 400L)
    }

    private fun updateSwipePreview(direction: SwipeDirection?) {
        val box = down
        val action = if (box != null && direction != null) box.spec.swipeAction(direction) else null
        when {
            action is KeyAction.Computer && box != null -> showSwipePreview(box, action.key.label)
            action is KeyAction.SetMode && box != null -> showSwipePreview(
                box,
                if (action.mode == KeyboardMode.ZHUYIN) "注" else "En",
            )
            direction != null -> {
                previewBox = null
                keyPreview.hide()
            }
            box != null -> armPreview(box)
        }
    }

    private fun showSwipePreview(box: KeyBox, text: String) {
        if (!keyPreviewEnabled || !isAttachedToWindow) return
        previewLinger = false
        previewBox = null
        keyPreview.cancelDismiss()
        keyPreview.show(text, box.rect, width)
    }

    /**
     * Commits the key still under a finger so a second finger can type.
     * A modifier that is already held stays down. A key the hold timer already sent is not sent again.
     */
    private fun commitHeld(eventTime: Long) {
        if (trackpad) {
            release()
            return
        }
        if (onLetterPick != null) {
            val token = down?.spec?.let { swipeKeyToken(it) }
            release()
            if (token != null) onLetterPick?.invoke(token)
            return
        }
        if (downPointer != NO_POINTER && modifierHolds.any { it.first == downPointer }) {
            detachHeldPointer()
            return
        }
        val spec = down?.spec ?: run {
            release()
            return
        }
        val direction = swipeDirection
        val unlocked = direction == null && unlockLockedModifiers(spec)
        val action = when {
            unlocked -> null
            direction != null -> fresh(spec.swipeAction(direction))
            else -> fresh(spec.action)
        }
        val linger = action != null && (direction != null || KeyPreview.visible(spec))
        release(lingerPreview = linger)
        if (action != null) fire(action, eventTime)
    }

    /** Drops the gesture under the current finger without lifting a modifier it is holding. */
    private fun detachHeldPointer() {
        stopRepeat()
        removeCallbacks(holdTrackpad)
        swipe = null
        swipeDirection = null
        down = null
        downPointer = NO_POINTER
        endPreview(linger = false)
        invalidate()
    }

    private fun beginHold(key: ComputerKey) {
        val pointer = downPointer
        if (pointer == NO_POINTER) return
        modifierHolds.removeAll { it.first == pointer }
        modifierHolds += pointer to key
        onModifierHold(key, true)
        removeCallbacks(longPress)
        removeCallbacks(holdSwipe)
        buzz()
        invalidate()
    }

    /** Forgets the finger. A lock stays until the key is tapped again. */
    private fun releaseHold(pointer: Int) {
        modifierHolds.removeAll { it.first == pointer }
    }

    /**
     * A tap on a key that locked Ctrl, Alt, Fn, or ⌘ releases those modifiers and types nothing.
     * Returns true when a lock was released.
     */
    private fun unlockLockedModifiers(spec: KeySpec): Boolean {
        val locked = ArrayList<ComputerKey>()
        val hold = spec.holdKey
        if (hold != null && hold.modifier && hold in lockedComputerKeys) locked += hold
        for (key in spec.swipes.values) {
            if (key.modifier && key in lockedComputerKeys && key !in locked) locked += key
        }
        if (locked.isEmpty()) return false
        for (key in locked) onModifierHold(key, false)
        return true
    }

    private fun releaseAllHolds() {
        val pointers = modifierHolds.map { it.first }.distinct()
        for (pointer in pointers) releaseHold(pointer)
    }

    private fun release(lingerPreview: Boolean = false) {
        releaseHold(downPointer)
        stopRepeat()
        removeCallbacks(holdTrackpad)
        val wasTracking = trackpad
        spaceCursor = null
        swipe = null
        swipeDirection = null
        trackpad = false
        down = null
        downPointer = NO_POINTER
        endPreview(lingerPreview && !wasTracking)
        invalidate()
        if (wasTracking) onTrackpadActive(false)
    }

    /**
     * Shows the floating label after the pressed-key frame is submitted.
     * Adding the popup window talks to the window manager and does not return until that call does,
     * so running it inside the touch handler holds the highlight back.
     */
    private fun armPreview(box: KeyBox?) {
        previewLinger = false
        val next = box?.takeIf { keyPreviewEnabled && KeyPreview.visible(it.spec) }
        if (next == null) {
            previewBox = null
            keyPreview.hide()
            return
        }
        previewBox = next
        keyPreview.cancelDismiss()
        if (previewFramePosted) return
        val observer = viewTreeObserver
        if (!observer.isAlive) {
            presentPreview()
            return
        }
        previewFramePosted = true
        if (Build.VERSION.SDK_INT >= 29) {
            observer.registerFrameCommitCallback { presentPreview() }
            return
        }
        observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                post {
                    if (observer.isAlive) observer.removeOnDrawListener(this)
                    presentPreview()
                }
            }
        })
    }

    private fun presentPreview() {
        previewFramePosted = false
        val box = previewBox ?: return
        previewBox = null
        if (!keyPreviewEnabled || !isAttachedToWindow) {
            previewLinger = false
            return
        }
        keyPreview.show(box.spec.label, box.rect, width)
        if (previewLinger || down != box) {
            previewLinger = false
            keyPreview.hideLater(KeyPreview.LINGER_MS)
        }
    }

    private fun endPreview(linger: Boolean) {
        if (linger && (previewFramePosted || keyPreview.isShowing)) {
            previewLinger = true
            if (!previewFramePosted) {
                previewLinger = false
                keyPreview.hideLater(KeyPreview.LINGER_MS)
            }
            return
        }
        previewLinger = false
        previewBox = null
        keyPreview.hide()
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
        rows = KeyboardLayouts.rows(
            mode,
            shifted = shiftLock.uppercase,
            base = base,
            fullWidth = fullWidthSymbols,
            capsLock = shiftLock.mode == ShiftMode.LOCKED,
            enter = enterChoice,
            latinTopNumberRow = latinTopNumberRow,
            computerKeys = computerKeys,
            computerMap = computerMap,
            numberPad = numberPad,
        )
        requestLayout()
        if (width > 0 && height > 0) {
            layoutKeys(width, height)
        }
        invalidate()
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

    private fun drawSwipeLabels(canvas: Canvas, box: KeyBox, draw: RectF, palette: KeyboardPalette) {
        val labels = box.spec.swipeLabels()
        if (labels.isEmpty()) return
        val active = if (box == down) swipeDirection else null
        val pad = dp(2).toFloat()
        for ((direction, text) in labels) {
            val key = box.spec.swipes[direction]
            val armed = (key != null && key in armedComputerKeys) || direction == active
            swipeLabel.textSize = sp(8f)
            swipeLabel.color = if (armed) palette.text else palette.hint
            val textWidth = swipeLabel.measureText(text)
            val textHeight = swipeLabel.descent() - swipeLabel.ascent()
            val alongTop = direction == SwipeDirection.UP ||
                direction == SwipeDirection.UP_LEFT ||
                direction == SwipeDirection.UP_RIGHT
            val alongBottom = direction == SwipeDirection.DOWN ||
                direction == SwipeDirection.DOWN_LEFT ||
                direction == SwipeDirection.DOWN_RIGHT
            val baseline = when {
                alongTop -> draw.top + pad - swipeLabel.ascent()
                alongBottom -> draw.bottom - pad - swipeLabel.descent()
                else -> draw.centerY() - (swipeLabel.ascent() + swipeLabel.descent()) / 2f
            }
            val alignLeft = direction == SwipeDirection.LEFT ||
                direction == SwipeDirection.UP_LEFT ||
                direction == SwipeDirection.DOWN_LEFT
            val alignRight = direction == SwipeDirection.RIGHT ||
                direction == SwipeDirection.UP_RIGHT ||
                direction == SwipeDirection.DOWN_RIGHT
            swipeLabel.textAlign = when {
                alignLeft -> Paint.Align.LEFT
                alignRight -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val x = when {
                alignLeft -> draw.left + pad
                alignRight -> draw.right - pad
                else -> draw.centerX()
            }
            if (armed) {
                val left = when (swipeLabel.textAlign) {
                    Paint.Align.LEFT -> x
                    Paint.Align.RIGHT -> x - textWidth
                    else -> x - textWidth / 2f
                }
                val top = baseline + swipeLabel.ascent()
                fill.color = palette.accent
                canvas.drawRoundRect(
                    left - dp(1),
                    top,
                    left + textWidth + dp(1),
                    top + textHeight,
                    dp(2).toFloat(),
                    dp(2).toFloat(),
                    fill,
                )
            }
            canvas.drawText(text, x, baseline, swipeLabel)
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
        removeCallbacks(holdSwipe)
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
