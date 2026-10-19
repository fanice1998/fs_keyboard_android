package com.fskeyboard.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
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
    private var pending = ImeUiState()

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
            fallbackBottom = navigationFallback(),
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
        win.decorView.setOnApplyWindowInsetsListener { _, insets ->
            applyNavigationInset(insets)
            insets
        }
    }

    private fun applyNavigationInset(insets: WindowInsets) {
        val root = inputRoot ?: return
        val spacer = navigationSpacer ?: return
        val resolved = if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout(),
            )
            KeyboardInsets.resolve(bars.left, bars.right, bars.bottom, navigationFallback())
        } else {
            @Suppress("DEPRECATION")
            val left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            val right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            KeyboardInsets.resolve(left, right, bottom, navigationFallback())
        }
        root.setPadding(resolved.left, 0, resolved.right, 0)
        val params = spacer.layoutParams
        if (params.height != resolved.bottom) {
            params.height = resolved.bottom
            spacer.layoutParams = params
        }
    }

    private fun navigationFallback(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id == 0) return 0
        return resources.getDimensionPixelSize(id)
    }

    private fun switchToAnotherKeyboard() {
        if (!switchToNextInputMethod(false)) {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setCandidatesViewShown(false)
        controller.onStartInput(restarting)
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
    }
}
