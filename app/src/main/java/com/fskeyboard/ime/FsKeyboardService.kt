package com.fskeyboard.ime

import android.content.res.Resources
import android.graphics.Color
import java.lang.ref.WeakReference
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fskeyboard.ime.candidate.CandidateBarView
import com.fskeyboard.ime.candidate.CandidateController
import com.fskeyboard.ime.candidate.CandidatePanelView
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.ImeUiState
import com.fskeyboard.ime.candidate.clearSystemClipboard
import com.fskeyboard.ime.engine.RustZhuyinLexicon
import com.fskeyboard.ime.keyboard.EnterActions
import com.fskeyboard.ime.keyboard.EnterChoice
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardView
import com.fskeyboard.ime.latency.FrameLatencyProbe
import com.fskeyboard.ime.latency.KeyLatency
import com.fskeyboard.ime.ui.KeyboardInsets
import com.fskeyboard.ime.rerank.CandidatePipeline
import com.fskeyboard.ime.settings.KeyboardPreferences
import com.fskeyboard.ime.rerank.ContextCandidateEncoder
import com.fskeyboard.ime.rerank.OnnxCandidateRanker
import com.fskeyboard.ime.rerank.RerankLogger
import com.fskeyboard.ime.rerank.RerankPreferences
import com.fskeyboard.ime.rerank.onnx.OnnxModelStore
import com.fskeyboard.ime.rerank.onnx.OnnxRerankModel
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.isNightMode

class FsKeyboardService : InputMethodService() {
    private val lexicon = RustZhuyinLexicon()
    private lateinit var pipeline: CandidatePipeline
    private lateinit var controller: CandidateController
    private var keyboard: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null
    private var candidatePanel: CandidatePanelView? = null
    private var panelOpen = false
    private var inputRoot: LinearLayout? = null
    private var navigationSpacer: View? = null
    private var latestInsets: WindowInsets? = null
    private var insetsListenerInstalled = false
    private var appliedInsetStamp = Long.MIN_VALUE
    private var navFrameResolved = false
    private var cachedImeNav: WeakReference<View>? = null
    private val navigationBarFramePx by lazy { navigationBarDimension("navigation_bar_frame_height") }
    private val navigationBarHeightPx by lazy { navigationBarDimension("navigation_bar_height") }
    private var pending = ImeUiState()
    private val latencyProbe = FrameLatencyProbe(KeyLatency.stats) { Log.i(TAG, it) }
    private var framesWatched = false
    private val relayoutInsets = ViewTreeObserver.OnGlobalLayoutListener {
        val insets = latestInsets ?: return@OnGlobalLayoutListener
        if (insetStamp(insets) == appliedInsetStamp && navFrameResolved) return@OnGlobalLayoutListener
        applyNavigationInset(insets)
    }

    override fun onCreate() {
        super.onCreate()
        configureWindow()
        val preferences = RerankPreferences(this)
        val ranker = OnnxCandidateRanker(
            model = OnnxRerankModel(
                store = OnnxModelStore(this),
                // Character features. A model that takes raw strings uses DirectStringEncoder().
                encoder = ContextCandidateEncoder(),
            ),
            enabled = preferences::isEnabled,
            logger = RerankLogger { message -> Log.i(TAG, message) },
        )
        ranker.load()
        pipeline = CandidatePipeline(reranker = ranker)
        controller = CandidateController(
            context = this,
            lexicon = lexicon,
            pipeline = pipeline,
            connection = { currentInputConnection },
            editorInfo = { currentInputEditorInfo },
            onUi = ::render,
            letterMode = { KeyboardPreferences(this).letterMode() },
        )
        controller.onLetterMode = { mode -> KeyboardPreferences(this).setLetterMode(mode) }
        controller.start()
    }

    override fun onDestroy() {
        if (framesWatched) {
            runCatching { window?.window?.removeOnFrameMetricsAvailableListener(latencyProbe) }
            framesWatched = false
        }
        window?.window?.decorView?.viewTreeObserver?.let { observer ->
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(relayoutInsets)
        }
        if (::controller.isInitialized) {
            controller.close()
        }
        if (::pipeline.isInitialized) {
            pipeline.close()
        }
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        configureWindow()
        panelOpen = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KeyboardPalette.of(isNightMode()).background)
            clipChildren = false
            clipToPadding = false
        }
        candidateBar = CandidateBarView(this).apply {
            onCandidate = { controller.onCandidate(it) }
            onExpand = { showPanel(!panelOpen) }
            onClearClipboard = {
                if (clearSystemClipboard(this@FsKeyboardService)) controller.dismissClipboard()
            }
        }
        candidatePanel = CandidatePanelView(this).apply {
            visibility = View.GONE
            onCandidate = {
                showPanel(false)
                controller.onCandidate(it)
            }
        }
        keyboard = KeyboardView(this).apply {
            val preferences = KeyboardPreferences(this@FsKeyboardService)
            rowHeightDp = preferences.rowHeightDp()
            fullWidthSymbols = preferences.fullWidthSymbols()
            latinTopNumberRow = preferences.latinTopNumberRow()
            computerKeys = preferences.computerKeys()
            computerMap = preferences.computerKeyMap()
            vibrationEnabled = preferences.vibrationEnabled()
            keyPreviewEnabled = preferences.keyPreviewEnabled()
            onTyped = latencyProbe::keyHandled
            trackpadAllowed = { controller.canMoveCursor() }
            onTrackpadStart = { controller.beginCursor() }
            onCursorGlide = { dx, dy -> controller.moveCursor(dx, dy) }
            onTrackpadActive = { active ->
                candidateBar?.alpha = if (active) 0.35f else 1f
                if (!active) controller.endCursor()
            }
            onKey = { action ->
                if (action == KeyAction.NextInputMethod) {
                    switchToAnotherKeyboard()
                } else {
                    controller.onKey(action)
                }
            }
            enterChoice = EnterActions.choose(currentInputEditorInfo)
        }
        controller.onComputerModifiers = { armed ->
            keyboard?.armedComputerKeys = armed
        }
        controller.onLockedModifiers = { locked ->
            keyboard?.lockedComputerKeys = locked
        }
        keyboard?.onModifierHold = { key, down ->
            controller.setModifierHeld(key, down)
        }
        val spacer = View(this)
        val initial = KeyboardInsets.resolve(
            left = 0,
            right = 0,
            reportedBottom = null,
            fallbackBottom = initialBottomClearance(),
        )
        root.addView(
            candidateBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        // The panel lies over the keys, so opening it does not change the keyboard height.
        val keys = FrameLayout(this).apply {
            addView(
                keyboard,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                candidatePanel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(
            keys,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            spacer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                initial.bottom,
            ),
        )
        inputRoot = root
        navigationSpacer = spacer
        window?.window?.decorView?.requestApplyInsets()
        watchFrames()
        render(pending)
        return root
    }

    /** Key latency is a measurement. A window that cannot report frames still types. */
    private fun watchFrames() {
        if (framesWatched) return
        val win = window?.window ?: return
        try {
            win.addOnFrameMetricsAvailableListener(latencyProbe, Handler(Looper.getMainLooper()))
            framesWatched = true
        } catch (error: RuntimeException) {
            Log.i(TAG, "frame metrics unavailable ${error.javaClass.simpleName}")
        }
    }

    private fun configureWindow() {
        val win = window?.window ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            win.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            win.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            win.isNavigationBarContrastEnforced = false
        }
        if (insetsListenerInstalled) return
        insetsListenerInstalled = true
        win.decorView.setOnApplyWindowInsetsListener { _, insets ->
            applyNavigationInset(insets)
            insets
        }
        win.decorView.viewTreeObserver.addOnGlobalLayoutListener(relayoutInsets)
    }

    private fun applyNavigationInset(insets: WindowInsets) {
        latestInsets = insets
        val stamp = insetStamp(insets)
        if (stamp != appliedInsetStamp) {
            navFrameResolved = false
            cachedImeNav = null
        }
        val root = inputRoot ?: return
        val spacer = navigationSpacer ?: return
        val embedded = embeddedNavigationBarHeight(allowSearch = !navFrameResolved)
        navFrameResolved = true
        appliedInsetStamp = stamp
        val frame = systemBarFrame()
        val samsung = isSamsung()
        val bottom = if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.navigationBars())
            val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
            val mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
            val tappable = insets.getInsets(WindowInsets.Type.tappableElement())
            val caption = insets.getInsets(WindowInsets.Type.captionBar())
            root.setPadding(
                maxOf(bars.left, cutout.left),
                0,
                maxOf(bars.right, cutout.right),
                0,
            )
            val clearance = KeyboardInsets.bottomClearance(
                navigationBars = bars.bottom,
                captionBar = caption.bottom,
                mandatoryGestures = mandatory.bottom,
                tappableElements = tappable.bottom,
                embeddedBar = embedded,
                systemFrame = frame,
                preferSystemFrame = samsung,
            )
            logClearance(
                clearance,
                "nav=${bars.bottom} caption=${caption.bottom} mandatory=${mandatory.bottom} " +
                    "tappable=${tappable.bottom} embedded=$embedded frame=$frame samsung=$samsung",
            )
            clearance
        } else {
            @Suppress("DEPRECATION")
            val left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            val right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom
            root.setPadding(left, 0, right, 0)
            val clearance = KeyboardInsets.bottomClearance(
                navigationBars = bottomInset,
                captionBar = 0,
                mandatoryGestures = 0,
                tappableElements = 0,
                embeddedBar = embedded,
                systemFrame = frame,
                preferSystemFrame = samsung,
            )
            logClearance(
                clearance,
                "nav=$bottomInset caption=0 mandatory=0 tappable=0 " +
                    "embedded=$embedded frame=$frame samsung=$samsung",
            )
            clearance
        }
        val params = spacer.layoutParams
        if (params.height != bottom) {
            params.height = bottom
            spacer.layoutParams = params
        }
    }

    private fun logClearance(clearance: Int, detail: String) {
        val spacer = navigationSpacer ?: return
        if (spacer.layoutParams?.height == clearance) return
        Log.i(TAG, "keyboard bottom clearance=$clearance $detail")
    }

    private fun initialBottomClearance(): Int {
        return KeyboardInsets.bottomClearance(
            navigationBars = navigationBarHeightPx,
            captionBar = 0,
            mandatoryGestures = 0,
            tappableElements = 0,
            embeddedBar = 0,
            systemFrame = systemBarFrame(),
            preferSystemFrame = isSamsung(),
        )
    }

    private fun embeddedNavigationBarHeight(allowSearch: Boolean): Int {
        val decor = window?.window?.decorView as? ViewGroup ?: return 0
        val background = decor.findViewById<View>(android.R.id.navigationBarBackground)
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.height ?: 0
        val cached = cachedImeNav?.get()?.takeIf { it.isAttachedToWindow }
        val frame = cached ?: if (allowSearch) {
            findImeNavigationFrame(decor)?.also { cachedImeNav = WeakReference(it) }
        } else {
            null
        }
        val imeNav = frame?.takeIf { it.visibility == View.VISIBLE }?.height ?: 0
        return maxOf(background, imeNav)
    }

    private fun findImeNavigationFrame(view: View): View? {
        if (view.javaClass.name == IME_NAVIGATION_FRAME) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findImeNavigationFrame(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun systemBarFrame(): Int = maxOf(navigationBarFramePx, navigationBarHeightPx)

    private fun insetStamp(insets: WindowInsets): Long {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val caption = insets.getInsets(WindowInsets.Type.captionBar()).bottom
            val mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
            val tappable = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
            return (bars.toLong() shl 48) or
                (caption.toLong() shl 32) or
                (mandatory.toLong() shl 16) or
                tappable.toLong()
        }
        @Suppress("DEPRECATION")
        return insets.systemWindowInsetBottom.toLong()
    }

    private fun navigationBarDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id == 0) return 0
        return try {
            resources.getDimensionPixelSize(id)
        } catch (_: Resources.NotFoundException) {
            0
        }
    }

    private fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private fun switchToAnotherKeyboard() {
        if (!switchToNextInputMethod(false)) {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setCandidatesViewShown(false)
        val preferences = KeyboardPreferences(this)
        keyboard?.rowHeightDp = preferences.rowHeightDp()
        keyboard?.fullWidthSymbols = preferences.fullWidthSymbols()
        keyboard?.latinTopNumberRow = preferences.latinTopNumberRow()
        keyboard?.computerKeys = preferences.computerKeys()
        keyboard?.computerMap = preferences.computerKeyMap()
        keyboard?.vibrationEnabled = preferences.vibrationEnabled()
        keyboard?.keyPreviewEnabled = preferences.keyPreviewEnabled()
        keyboard?.enterChoice = EnterActions.choose(info)
        controller.onStartInput(restarting)
        window?.window?.decorView?.requestApplyInsets()
        latestInsets?.let(::applyNavigationInset)
    }

    override fun onFinishInput() {
        if (::controller.isInitialized) {
            controller.onFinishInput()
        }
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (::controller.isInitialized) {
            controller.onUpdateSelection(
                oldSelStart,
                oldSelEnd,
                newSelStart,
                newSelEnd,
                candidatesStart,
                candidatesEnd,
            )
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun render(state: ImeUiState) {
        pending = state
        traced("ime.render") {
            keyboard?.setMode(state.mode, state.base)
            val enterChoice = if (state.hasUnconfirmedText) {
                EnterChoice(confirming = true)
            } else {
                EnterActions.choose(currentInputEditorInfo)
            }
            keyboard?.enterChoice = enterChoice
            candidateBar?.render(state.candidates)
            val clipboardOnly = state.candidates.candidates.singleOrNull()?.source == CandidateSource.CLIPBOARD
            when {
                state.candidates.candidates.isEmpty() || clipboardOnly -> showPanel(false)
                panelOpen -> candidatePanel?.render(state.candidates.candidates)
            }
        }
    }

    /** Opens every candidate over the keys. It stays closed while there are no candidates. */
    private fun showPanel(open: Boolean) {
        val candidates = pending.candidates.candidates
        panelOpen = open && candidates.isNotEmpty()
        if (panelOpen) candidatePanel?.render(candidates)
        candidatePanel?.visibility = if (panelOpen) View.VISIBLE else View.GONE
        candidateBar?.expanded = panelOpen
    }

    private companion object {
        const val TAG = "FsKeyboard"
        const val IME_NAVIGATION_FRAME =
            "android.inputmethodservice.navigationbar.NavigationBarFrame"
    }
}
