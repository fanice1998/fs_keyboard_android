package com.fskeyboard.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.fskeyboard.ime.candidate.CandidateBarView
import com.fskeyboard.ime.candidate.CandidateController
import com.fskeyboard.ime.candidate.ImeUiState
import com.fskeyboard.ime.engine.RimeEngine
import com.fskeyboard.ime.keyboard.KeyboardView
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
    private var pending = ImeUiState()

    override fun onCreate() {
        super.onCreate()
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KeyboardPalette.of(isNightMode()).background)
        }
        candidateBar = CandidateBarView(this).apply {
            onCandidate = { controller.onCandidate(it) }
            onToggleScript = { controller.onToggleScript() }
        }
        keyboard = KeyboardView(this).apply {
            onKey = { controller.onKey(it) }
        }
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
        render(pending)
        return root
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
