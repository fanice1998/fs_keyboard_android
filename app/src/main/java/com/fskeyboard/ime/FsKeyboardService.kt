package com.fskeyboard.ime

import android.content.res.Resources
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.fskeyboard.ime.candidate.CandidateBarView
import com.fskeyboard.ime.candidate.CandidateController
import com.fskeyboard.ime.candidate.ImeUiState
import com.fskeyboard.ime.engine.RimeEngine
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardView
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
    private val engine = RimeEngine()
    private lateinit var pipeline: CandidatePipeline
    private lateinit var controller: CandidateController
    private var keyboard: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null
    private var inputRoot: LinearLayout? = null
    private var navigationSpacer: View? = null
    private var latestInsets: WindowInsets? = null
    private var insetsListenerInstalled = false
    private var pending = ImeUiState()
    private val relayoutInsets = ViewTreeObserver.OnGlobalLayoutListener {
        latestInsets?.let(::applyNavigationInset)
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
            engine = engine,
            pipeline = pipeline,
            connection = { currentInputConnection },
            editorInfo = { currentInputEditorInfo },
            onUi = ::render,
        )
        controller.start()
    }

    override fun onDestroy() {
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KeyboardPalette.of(isNightMode()).background)
        }
        candidateBar = CandidateBarView(this).apply {
            onCandidate = { controller.onCandidate(it) }
            onToggleScript = { controller.onToggleScript() }
        }
        keyboard = KeyboardView(this).apply {
            rowHeightDp = KeyboardPreferences(this@FsKeyboardService).rowHeightDp()
            onKey = { action ->
                if (action == KeyAction.NextInputMethod) {
                    switchToAnotherKeyboard()
                } else {
                    controller.onKey(action)
                }
            }
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
        root.addView(
            keyboard,
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
        render(pending)
        return root
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
        val root = inputRoot ?: return
        val spacer = navigationSpacer ?: return
        val embedded = embeddedNavigationBarHeight()
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
            navigationBars = navigationBarDimension("navigation_bar_height"),
            captionBar = 0,
            mandatoryGestures = 0,
            tappableElements = 0,
            embeddedBar = 0,
            systemFrame = systemBarFrame(),
            preferSystemFrame = isSamsung(),
        )
    }

    private fun embeddedNavigationBarHeight(): Int {
        val decor = window?.window?.decorView as? ViewGroup ?: return 0
        val background = decor.findViewById<View>(android.R.id.navigationBarBackground)
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.height ?: 0
        val imeNav = findImeNavigationFrame(decor)
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.height ?: 0
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

    private fun systemBarFrame(): Int {
        return maxOf(
            navigationBarDimension("navigation_bar_frame_height"),
            navigationBarDimension("navigation_bar_height"),
        )
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
        keyboard?.rowHeightDp = KeyboardPreferences(this).rowHeightDp()
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

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun render(state: ImeUiState) {
        pending = state
        keyboard?.setMode(state.mode)
        candidateBar?.render(state.candidates)
    }

    private companion object {
        const val TAG = "FsKeyboard"
        const val IME_NAVIGATION_FRAME =
            "android.inputmethodservice.navigationbar.NavigationBarFrame"
    }
}
