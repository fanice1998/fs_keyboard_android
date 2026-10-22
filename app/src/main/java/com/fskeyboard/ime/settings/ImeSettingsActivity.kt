package com.fskeyboard.ime.settings

import android.app.Activity
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.fskeyboard.ime.R
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateBarView
import com.fskeyboard.ime.candidate.CandidateUiState
import com.fskeyboard.ime.keyboard.KeyPreviewOverlayView
import com.fskeyboard.ime.keyboard.KeyboardHeight
import com.fskeyboard.ime.keyboard.KeyboardView
import com.fskeyboard.ime.latency.KeyLatency
import com.fskeyboard.ime.rerank.RerankPreferences
import com.fskeyboard.ime.rerank.onnx.OnnxModelStore
import com.fskeyboard.ime.ui.isNightMode

class ImeSettingsActivity : Activity() {
    private lateinit var keyboardPreferences: KeyboardPreferences
    private lateinit var heightSeek: SeekBar
    private lateinit var heightValue: TextView
    private lateinit var previewKeyboard: KeyboardView
    private lateinit var latencyValue: TextView
    private var settingsRoot: View? = null

    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { applySafeArea() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ime_settings)
        val root = findViewById<View>(R.id.settings_root)
        settingsRoot = root
        root.setOnApplyWindowInsetsListener { _, insets ->
            applySafeArea()
            insets
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(onLayout)
        root.requestApplyInsets()

        val preferences = RerankPreferences(this)
        val toggle = findViewById<Switch>(R.id.rerank_switch)
        toggle.isChecked = preferences.isEnabled()
        toggle.setOnCheckedChangeListener { _, checked ->
            preferences.setEnabled(checked)
        }
        val model = findViewById<TextView>(R.id.rerank_model)
        model.text = if (OnnxModelStore(this).hasModel()) {
            getString(R.string.rerank_model_ready)
        } else {
            getString(R.string.rerank_model_missing)
        }

        latencyValue = findViewById(R.id.key_latency_value)
        findViewById<Button>(R.id.key_latency_reset).setOnClickListener {
            KeyLatency.stats.clear()
            showLatency()
        }

        keyboardPreferences = KeyboardPreferences(this)
        val fullWidth = findViewById<Switch>(R.id.full_width_switch)
        fullWidth.isChecked = keyboardPreferences.fullWidthSymbols()
        fullWidth.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setFullWidthSymbols(checked)
        }
        val latinTopNumber = findViewById<Switch>(R.id.latin_top_number_row_switch)
        latinTopNumber.isChecked = keyboardPreferences.latinTopNumberRow()
        latinTopNumber.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setLatinTopNumberRow(checked)
            previewKeyboard.latinTopNumberRow = checked
        }
        val keyPopup = findViewById<Switch>(R.id.key_popup_switch)
        keyPopup.isChecked = keyboardPreferences.keyPopupEnabled()
        keyPopup.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setKeyPopupEnabled(checked)
            previewKeyboard.keyPopupEnabled = checked
        }
        val vibration = findViewById<Switch>(R.id.vibration_switch)
        vibration.isChecked = keyboardPreferences.vibrationEnabled()
        vibration.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setVibrationEnabled(checked)
            previewKeyboard.vibrationEnabled = checked
        }
        heightValue = findViewById(R.id.keyboard_height_value)
        heightSeek = findViewById(R.id.keyboard_height)
        heightSeek.min = KeyboardHeight.MIN_ROW_DP
        heightSeek.max = KeyboardHeight.MAX_ROW_DP
        val saved = keyboardPreferences.rowHeightDp()
        installPreview(saved)
        heightSeek.progress = saved
        showHeight(saved)
        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                previewKeyboard.rowHeightDp = progress
                showHeight(progress)
                if (fromUser) keyboardPreferences.setRowHeightDp(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                keyboardPreferences.setRowHeightDp(seekBar.progress, flush = true)
                showHeight(seekBar.progress)
            }
        })
    }

    override fun onDestroy() {
        settingsRoot?.viewTreeObserver?.let { observer ->
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(onLayout)
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        showLatency()
    }

    override fun onPause() {
        if (::keyboardPreferences.isInitialized && ::heightSeek.isInitialized) {
            keyboardPreferences.setRowHeightDp(heightSeek.progress, flush = true)
        }
        super.onPause()
    }

    private fun showLatency() {
        val summary = KeyLatency.stats.summary()
        latencyValue.text = if (summary == null) {
            getString(R.string.key_latency_empty)
        } else {
            getString(
                R.string.key_latency_value,
                summary.count,
                summary.p50Millis,
                summary.p95Millis,
                summary.p99Millis,
                summary.maxMillis,
            )
        }
    }

    private fun showHeight(rowDp: Int) {
        heightValue.text = getString(
            R.string.keyboard_height_value,
            KeyboardHeight.percentOfDefault(rowDp),
        )
    }

    private fun installPreview(rowDp: Int) {
        val host = findViewById<LinearLayout>(R.id.keyboard_height_preview)
        val previewContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            clipChildren = false
            clipToPadding = false
        }
        val bar = CandidateBarView(this)
        bar.render(
            CandidateUiState(
                candidates = listOf(
                    Candidate("你", engineIndex = 0),
                    Candidate("妳", engineIndex = 1),
                    Candidate("泥", engineIndex = 2),
                ),
            ),
        )
        val keyboard = KeyboardView(this).apply {
            rowHeightDp = rowDp
            latinTopNumberRow = keyboardPreferences.latinTopNumberRow()
            fullWidthSymbols = keyboardPreferences.fullWidthSymbols()
            vibrationEnabled = keyboardPreferences.vibrationEnabled()
            keyPopupEnabled = keyboardPreferences.keyPopupEnabled()
        }
        val overlay = KeyPreviewOverlayView(this)
        keyboard.onShowKeyPreview = { label, keyRectInKeyboard ->
            val keyLoc = IntArray(2)
            keyboard.getLocationInWindow(keyLoc)
            val overlayLoc = IntArray(2)
            overlay.getLocationInWindow(overlayLoc)
            val dx = (keyLoc[0] - overlayLoc[0]).toFloat()
            val dy = (keyLoc[1] - overlayLoc[1]).toFloat()
            val rectInOverlay = RectF(
                keyRectInKeyboard.left + dx,
                keyRectInKeyboard.top + dy,
                keyRectInKeyboard.right + dx,
                keyRectInKeyboard.bottom + dy,
            )
            overlay.show(label, rectInOverlay, keyboard.isNightMode())
        }
        keyboard.onDismissKeyPreview = { immediate ->
            if (immediate) overlay.dismissImmediately() else overlay.dismissDelayed(80L)
        }

        val width = LinearLayout.LayoutParams.MATCH_PARENT
        val height = LinearLayout.LayoutParams.WRAP_CONTENT
        stack.addView(bar, LinearLayout.LayoutParams(width, height))
        stack.addView(keyboard, LinearLayout.LayoutParams(width, height))
        previewContainer.addView(
            stack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        previewContainer.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        host.addView(previewContainer, LinearLayout.LayoutParams(width, height))
        previewKeyboard = keyboard
    }

    private fun applySafeArea() {
        val root = settingsRoot ?: return
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0 || root.width <= 0 || root.height <= 0) return
        val location = IntArray(2)
        root.getLocationInWindow(location)
        val inset = systemInsets() ?: SettingsPadding(0, 0, 0, 0)
        val padding = SettingsSafeArea.padding(
            contentLeft = location[0],
            contentTop = location[1],
            contentRight = location[0] + root.width,
            contentBottom = location[1] + root.height,
            windowWidth = decor.width,
            windowHeight = decor.height,
            titleBottom = titleBottomInWindow(root),
            insetLeft = inset.left,
            insetTop = inset.top,
            insetRight = inset.right,
            insetBottom = inset.bottom,
        )
        if (
            root.paddingLeft == padding.left &&
            root.paddingTop == padding.top &&
            root.paddingRight == padding.right &&
            root.paddingBottom == padding.bottom
        ) {
            return
        }
        root.setPadding(padding.left, padding.top, padding.right, padding.bottom)
    }

    /** Bottom of a framework title that is not part of the settings content. */
    private fun titleBottomInWindow(content: View): Int {
        val decor = window.decorView
        var bottom = 0
        for (name in listOf("action_bar_container", "title_container", "action_bar")) {
            val id = resources.getIdentifier(name, "id", "android")
            if (id == 0) continue
            bottom = maxOf(bottom, outsideBottom(decor.findViewById<View>(id), content))
        }
        val title = decor.findViewById<View>(android.R.id.title)
        val titleParent = title?.parent as? View
        bottom = maxOf(
            bottom,
            outsideBottom(titleParent?.takeUnless { it === decor }, content),
            outsideBottom(title, content),
        )
        return bottom
    }

    private fun outsideBottom(view: View?, content: View): Int {
        if (view == null || view.visibility != View.VISIBLE || view.height <= 0) return 0
        var parent: View? = view
        while (parent != null) {
            if (parent === content) return 0
            parent = parent.parent as? View
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return location[1] + view.height
    }

    private fun systemInsets(): SettingsPadding? {
        val insets = window.decorView.rootWindowInsets ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return SettingsPadding(bars.left, bars.top, bars.right, bars.bottom)
        }
        @Suppress("DEPRECATION")
        var left = insets.systemWindowInsetLeft
        @Suppress("DEPRECATION")
        var top = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        var right = insets.systemWindowInsetRight
        @Suppress("DEPRECATION")
        var bottom = insets.systemWindowInsetBottom
        if (Build.VERSION.SDK_INT >= 28) {
            val cutout = insets.displayCutout
            if (cutout != null) {
                left = maxOf(left, cutout.safeInsetLeft)
                top = maxOf(top, cutout.safeInsetTop)
                right = maxOf(right, cutout.safeInsetRight)
                bottom = maxOf(bottom, cutout.safeInsetBottom)
            }
        }
        return SettingsPadding(left, top, right, bottom)
    }
}
