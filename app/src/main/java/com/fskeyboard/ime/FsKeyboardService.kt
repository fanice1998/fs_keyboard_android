package com.fskeyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateBarView
import com.fskeyboard.ime.candidate.CompositionPreviewPopup
import com.fskeyboard.ime.candidate.PartialLexicon
import com.fskeyboard.ime.candidate.CandidateController
import com.fskeyboard.ime.candidate.CandidatePanelView
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.CandidateWeightMenu
import com.fskeyboard.ime.candidate.ImeUiState
import com.fskeyboard.ime.candidate.clearSystemClipboard
import com.fskeyboard.ime.engine.ChiaKeySession
import com.fskeyboard.ime.engine.ChiaKeyUnigramScan
import com.fskeyboard.ime.engine.IdleZhuyinLexicon
import com.fskeyboard.ime.engine.LexiconClient
import com.fskeyboard.ime.engine.LexiconInstaller
import java.io.File
import com.fskeyboard.ime.keyboard.EnterActions
import com.fskeyboard.ime.keyboard.EnterChoice
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardView
import com.fskeyboard.ime.latency.FrameLatencyProbe
import com.fskeyboard.ime.latency.KeyLatency
import com.fskeyboard.ime.ui.KeyboardInsets
import com.fskeyboard.ime.ui.KeyStage
import com.fskeyboard.ime.voice.SystemSpeech
import com.fskeyboard.ime.voice.VoiceInputView
import com.fskeyboard.ime.voice.VoicePermissionActivity
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
    private var chiakey: ChiaKeySession? = null
    @Volatile private var downloadingLexicon = false
    private val indexGate = Any()
    private var indexGeneration = 0
    private lateinit var pipeline: CandidatePipeline
    private lateinit var controller: CandidateController
    private var keyboard: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null
    private var compositionPreview: CompositionPreviewPopup? = null
    private var candidatePanel: CandidatePanelView? = null
    private var voiceView: VoiceInputView? = null
    private var speech: SystemSpeech? = null
    private var voiceOpen = false
    private var panelOpen = false
    private var lastClearance = -1
    private var lastNavigationBars = 0
    private var loggedClearance = Int.MIN_VALUE
    private var loggedDetail = ""
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
        if (insetStamp(insets) == appliedInsetStamp && navFrameResolved) {
            if (lastClearance >= 0) updateSpacer(lastClearance, lastNavigationBars)
            return@OnGlobalLayoutListener
        }
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
        val session = ChiaKeySession(File(filesDir, "chiakey"))
        chiakey = session
        session.open()
        controller = CandidateController(
            context = this,
            lexicon = IdleZhuyinLexicon,
            pipeline = pipeline,
            connection = { currentInputConnection },
            editorInfo = { currentInputEditorInfo },
            onUi = ::render,
            letterMode = { KeyboardPreferences(this).letterMode() },
            zhuyinEngine = session,
            chiakey = true,
            onRequestLexicon = { downloadLexicon() },
        )
        controller.onLetterMode = { mode -> KeyboardPreferences(this).setLetterMode(mode) }
        controller.start()
        startChiaKeyIndex()
    }

    override fun onDestroy() {
        compositionPreview?.release()
        compositionPreview = null
        destroySpeech()
        if (framesWatched) {
            runCatching { window?.window?.removeOnFrameMetricsAvailableListener(latencyProbe) }
            framesWatched = false
        }
        window?.window?.decorView?.viewTreeObserver?.let { observer ->
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(relayoutInsets)
        }
        chiakey?.close()
        if (::controller.isInitialized) {
            controller.close()
        }
        if (::pipeline.isInitialized) {
            pipeline.close()
        }
        super.onDestroy()
    }

    /**
     * Builds sentence guesses from the downloaded ChiaKey unigrams.
     * A newer scan replaces an older one. A missing database leaves the previous index.
     */
    private fun startChiaKeyIndex() {
        val generation = synchronized(indexGate) { ++indexGeneration }
        val root = File(filesDir, "chiakey")
        Thread({
            try {
                if (!File(root, "ChiaKeySource.db").isFile) return@Thread
                val builder = PartialLexicon.Builder()
                ChiaKeyUnigramScan.read(root) { qstring, text, probability ->
                    builder.add(qstring, text, probability)
                }
                val index = builder.build()
                val current = synchronized(indexGate) { indexGeneration }
                if (generation != current || !::controller.isInitialized) return@Thread
                Log.i(TAG, "chiakey unigram index ready: ${index.wordCount} words")
                controller.publishPartialLexicon(index)
            } catch (error: Throwable) {
                Log.w(TAG, "chiakey unigram index failed", error)
            }
        }, "fskeyboard-chiakey-index").start()
    }

    /** Consent already happened. A failed download leaves the previous file. */
    private fun downloadLexicon() {
        if (downloadingLexicon) return
        downloadingLexicon = true
        val root = File(filesDir, "chiakey")
        Thread({
            try {
                val release = LexiconClient.latestReleaseJson()
                val installed = LexiconInstaller(root) { url -> LexiconClient.bytes(url) }.install(release)
                if (installed) {
                    chiakey?.close()
                    chiakey?.open()
                    startChiaKeyIndex()
                }
            } catch (error: Exception) {
                Log.w(TAG, "lexicon download failed", error)
            } finally {
                downloadingLexicon = false
            }
        }, "fskeyboard-lexicon").start()
    }

    override fun onCreateInputView(): View {
        configureWindow()
        destroySpeech()
        voiceOpen = false
        panelOpen = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KeyboardPalette.of(isNightMode()).background)
            clipChildren = false
            clipToPadding = false
        }
        compositionPreview?.release()
        val bar = CandidateBarView(this).apply {
            onCandidate = { controller.onCandidate(it) }
            onExpand = { if (!voiceOpen) showPanel(!panelOpen) }
            onCandidateHold = { anchor, candidate -> showWeightMenu(anchor, candidate) }
            onClearClipboard = {
                if (clearSystemClipboard(this@FsKeyboardService)) controller.dismissClipboard()
            }
        }
        candidateBar = bar
        compositionPreview = CompositionPreviewPopup(bar)
        candidatePanel = CandidatePanelView(this).apply {
            visibility = View.GONE
            onCandidate = {
                showPanel(false)
                controller.onCandidate(it)
            }
            onCandidateHold = { anchor, candidate -> showWeightMenu(anchor, candidate) }
        }
        keyboard = KeyboardView(this).apply {
            val preferences = KeyboardPreferences(this@FsKeyboardService)
            rowHeightDp = preferences.rowHeightDp()
            fullWidthSymbols = preferences.fullWidthSymbols()
            latinTopNumberRow = preferences.latinTopNumberRow()
            zhuyinComputerLayout = preferences.zhuyinComputerLayout()
            split = isLandscape()
            computerKeys = preferences.computerKeys()
            computerMap = preferences.computerKeyMap()
            vibrationEnabled = preferences.vibrationEnabled()
            keyPreviewEnabled = preferences.keyPreviewEnabled()
            onTyped = latencyProbe::keyHandled
            trackpadAllowed = { controller.canMoveCursor() }
            onTrackpadStart = { controller.beginCursor() }
            onCursorGlide = { dx, dy -> controller.moveCursor(dx, dy) }
            onTrackpadActive = { active ->
                val faded = if (active) 0.35f else 1f
                candidateBar?.alpha = faded
                compositionPreview?.faded = active
                if (!active) controller.endCursor()
            }
            onKey = { action ->
                if (action == KeyAction.NextInputMethod) {
                    switchToAnotherKeyboard()
                } else {
                    controller.onKey(action)
                }
            }
            onVoice = { openVoice() }
            enterChoice = EnterActions.choose(currentInputEditorInfo)
        }
        voiceView = VoiceInputView(this).apply {
            visibility = View.GONE
            onMic = { toggleVoice() }
            onButton = { if (listening) cancelVoice() else closeVoice() }
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
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        // The panel and the voice screen lie over the keys. The stage keeps the keyboard height.
        val keys = KeyStage(this).apply {
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
            addView(
                voiceView,
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
                landscape = isLandscape(),
            )
            logClearance(
                clearance,
                "nav=${bars.bottom} caption=${caption.bottom} mandatory=${mandatory.bottom} " +
                    "tappable=${tappable.bottom} embedded=$embedded frame=$frame samsung=$samsung " +
                    "landscape=${isLandscape()}",
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
                landscape = isLandscape(),
            )
            logClearance(
                clearance,
                "nav=$bottomInset caption=0 mandatory=0 tappable=0 " +
                    "embedded=$embedded frame=$frame samsung=$samsung landscape=${isLandscape()}",
            )
            clearance
        }
        val navigationBars = if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        lastClearance = bottom
        lastNavigationBars = navigationBars
        updateSpacer(bottom, navigationBars)
    }

    /** Landscape key growth comes out of this gap, down to the real navigation bar. */
    private fun updateSpacer(clearance: Int, navigationBars: Int) {
        val spacer = navigationSpacer ?: return
        val height = if (isLandscape()) {
            KeyboardInsets.landscapeSpacer(clearance, navigationBars, keyboard?.splitGrowthPx() ?: 0)
        } else {
            clearance
        }
        val params = spacer.layoutParams ?: return
        if (params.height == height) return
        params.height = height
        spacer.layoutParams = params
    }

    private fun logClearance(clearance: Int, detail: String) {
        if (clearance == loggedClearance && detail == loggedDetail) return
        loggedClearance = clearance
        loggedDetail = detail
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
            landscape = isLandscape(),
        )
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
        keyboard?.zhuyinComputerLayout = preferences.zhuyinComputerLayout()
        keyboard?.split = isLandscape()
        keyboard?.computerKeys = preferences.computerKeys()
        keyboard?.computerMap = preferences.computerKeyMap()
        keyboard?.vibrationEnabled = preferences.vibrationEnabled()
        keyboard?.keyPreviewEnabled = preferences.keyPreviewEnabled()
        keyboard?.enterChoice = EnterActions.choose(info)
        controller.onStartInput(restarting)
        window?.window?.decorView?.requestApplyInsets()
        latestInsets?.let(::applyNavigationInset)
    }

    override fun onWindowHidden() {
        compositionPreview?.dismiss()
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        closeVoice()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboard?.split = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        latestInsets?.let(::applyNavigationInset)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun render(state: ImeUiState) {
        pending = state
        traced("ime.render") {
            keyboard?.setMode(state.mode, state.base, state.numberPad)
            val enterChoice = if (state.hasUnconfirmedText) {
                EnterChoice(confirming = true)
            } else {
                EnterActions.choose(currentInputEditorInfo)
            }
            keyboard?.enterChoice = enterChoice
            candidateBar?.render(state.candidates)
            compositionPreview?.show(state.preview)
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

    private fun showWeightMenu(anchor: View, candidate: Candidate) {
        if (candidate.source == CandidateSource.CLIPBOARD) return
        CandidateWeightMenu.show(anchor) { weight ->
            controller.adjustCandidate(candidate, weight)
        }
    }

    private fun openVoice() {
        if (::controller.isInitialized) controller.revertLastSpaceTap()
        showPanel(false)
        voiceOpen = true
        keyboard?.visibility = View.INVISIBLE
        val view = voiceView ?: return
        view.listening = false
        view.partial = ""
        view.message = if (!SystemSpeech.available(this)) getString(R.string.voice_unavailable) else ""
        view.visibility = View.VISIBLE
    }

    private fun toggleVoice() {
        val view = voiceView ?: return
        if (view.listening) {
            speech?.finish { text ->
                view.post {
                    if (!voiceOpen) return@post
                    if (text.isNotBlank() && ::controller.isInitialized) controller.commitVoice(text)
                    closeVoice()
                }
            }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            view.message = getString(R.string.voice_permission)
            val intent = Intent(this, VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        if (!SystemSpeech.available(this)) {
            view.message = getString(R.string.voice_unavailable)
            return
        }
        view.message = ""
        view.partial = ""
        view.listening = true
        val active = speech ?: SystemSpeech(this).also { speech = it }
        active.start(
            language = keyboard?.voiceLanguageTag() ?: "zh-TW",
            onPartial = { text -> view.partial = text },
            onFailure = {
                view.listening = false
                view.message = getString(R.string.voice_failed)
            },
        )
    }

    private fun cancelVoice() {
        speech?.cancel()
        voiceView?.listening = false
        voiceView?.partial = ""
    }

    private fun closeVoice() {
        destroySpeech()
        voiceOpen = false
        voiceView?.listening = false
        voiceView?.partial = ""
        voiceView?.message = ""
        voiceView?.visibility = View.GONE
        keyboard?.visibility = View.VISIBLE
    }

    private fun destroySpeech() {
        speech?.destroy()
        speech = null
    }

    private companion object {
        const val TAG = "FsKeyboard"
        const val IME_NAVIGATION_FRAME =
            "android.inputmethodservice.navigationbar.NavigationBarFrame"
    }
}
