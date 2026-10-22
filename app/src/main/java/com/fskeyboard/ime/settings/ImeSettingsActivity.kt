package com.fskeyboard.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.fskeyboard.ime.R
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateBarView
import com.fskeyboard.ime.candidate.CandidateUiState
import com.fskeyboard.ime.keyboard.ComputerKey
import com.fskeyboard.ime.keyboard.ComputerKeyMap
import com.fskeyboard.ime.keyboard.KeyboardHeight
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.KeyboardView
import com.fskeyboard.ime.keyboard.SwipeDirection
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.latency.KeyLatency
import com.fskeyboard.ime.rerank.RerankPreferences
import com.fskeyboard.ime.rerank.onnx.OnnxModelStore

class ImeSettingsActivity : Activity() {
    private lateinit var keyboardPreferences: KeyboardPreferences
    private lateinit var heightSeek: SeekBar
    private lateinit var heightValue: TextView
    private lateinit var previewKeyboard: KeyboardView
    private lateinit var editorKeyboard: KeyboardView
    private lateinit var letterLabel: TextView
    private lateinit var padCenter: TextView
    private lateinit var latencyValue: TextView
    private var settingsRoot: View? = null
    private var editorMap = ComputerKeyMap.DEFAULT
    private var editorLetter: Char? = null
    private val padButtons = linkedMapOf<SwipeDirection, Button>()
    private val padDirections: List<SwipeDirection?> = listOf(
        SwipeDirection.UP_LEFT,
        SwipeDirection.UP,
        SwipeDirection.UP_RIGHT,
        SwipeDirection.LEFT,
        null,
        SwipeDirection.RIGHT,
        SwipeDirection.DOWN_LEFT,
        SwipeDirection.DOWN,
        SwipeDirection.DOWN_RIGHT,
    )

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
            if (::editorKeyboard.isInitialized) editorKeyboard.latinTopNumberRow = checked
        }
        val computerEditor = findViewById<View>(R.id.computer_key_editor)
        val computerKeys = findViewById<Switch>(R.id.latin_computer_keys_switch)
        computerKeys.isChecked = keyboardPreferences.computerKeys()
        computerEditor.visibility = if (computerKeys.isChecked) View.VISIBLE else View.GONE
        computerKeys.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setComputerKeys(checked)
            previewKeyboard.computerKeys = checked
            computerEditor.visibility = if (checked) View.VISIBLE else View.GONE
        }
        val vibration = findViewById<Switch>(R.id.vibration_switch)
        vibration.isChecked = keyboardPreferences.vibrationEnabled()
        vibration.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setVibrationEnabled(checked)
            previewKeyboard.vibrationEnabled = checked
        }
        val keyPreview = findViewById<Switch>(R.id.key_preview_switch)
        keyPreview.isChecked = keyboardPreferences.keyPreviewEnabled()
        keyPreview.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setKeyPreviewEnabled(checked)
            previewKeyboard.keyPreviewEnabled = checked
        }
        heightValue = findViewById(R.id.keyboard_height_value)
        heightSeek = findViewById(R.id.keyboard_height)
        heightSeek.min = KeyboardHeight.MIN_ROW_DP
        heightSeek.max = KeyboardHeight.MAX_ROW_DP
        val saved = keyboardPreferences.rowHeightDp()
        installPreview(saved)
        installComputerEditor(saved)
        heightSeek.progress = saved
        showHeight(saved)
        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                previewKeyboard.rowHeightDp = progress
                if (::editorKeyboard.isInitialized) editorKeyboard.rowHeightDp = progress
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
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
            computerKeys = keyboardPreferences.computerKeys()
            fullWidthSymbols = keyboardPreferences.fullWidthSymbols()
            vibrationEnabled = keyboardPreferences.vibrationEnabled()
            keyPreviewEnabled = keyboardPreferences.keyPreviewEnabled()
        }
        val width = LinearLayout.LayoutParams.MATCH_PARENT
        val height = LinearLayout.LayoutParams.WRAP_CONTENT
        stack.addView(bar, LinearLayout.LayoutParams(width, height))
        stack.addView(keyboard, LinearLayout.LayoutParams(width, height))
        host.addView(stack, LinearLayout.LayoutParams(width, height))
        previewKeyboard = keyboard
    }

    private fun installComputerEditor(rowDp: Int) {
        val host = findViewById<LinearLayout>(R.id.computer_key_keyboard)
        editorMap = keyboardPreferences.computerKeyMap()
        letterLabel = findViewById(R.id.computer_key_letter)
        val keyboard = KeyboardView(this).apply {
            rowHeightDp = rowDp
            latinTopNumberRow = keyboardPreferences.latinTopNumberRow()
            vibrationEnabled = false
            keyPreviewEnabled = false
            computerMap = editorMap
            computerKeys = true
            setMode(KeyboardMode.LATIN)
        }
        keyboard.onLetterPick = { letter ->
            editorLetter = letter
            keyboard.markedLetter = letter
            refreshComputerPad()
        }
        host.addView(
            keyboard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        editorKeyboard = keyboard
        findViewById<Button>(R.id.computer_key_reset).setOnClickListener {
            updateComputerMap(ComputerKeyMap.DEFAULT)
        }
        buildComputerPad()
    }

    private fun buildComputerPad() {
        val pad = findViewById<GridLayout>(R.id.computer_key_pad)
        pad.removeAllViews()
        padButtons.clear()
        padDirections.forEachIndexed { index, direction ->
            val row = index / 3
            val column = index % 3
            val view = if (direction == null) {
                TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 22f
                    minHeight = dp(64)
                    padCenter = this
                }
            } else {
                val swipe = direction
                Button(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 13f
                    minHeight = dp(64)
                    minimumWidth = 0
                    minWidth = 0
                    setAllCaps(false)
                    transformationMethod = null
                    setOnClickListener { chooseComputerKey(swipe) }
                    padButtons[swipe] = this
                }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, 1),
                GridLayout.spec(column, 1, 1f),
            )
            params.width = 0
            params.height = GridLayout.LayoutParams.WRAP_CONTENT
            params.setMargins(dp(3), dp(3), dp(3), dp(3))
            pad.addView(view, params)
        }
        refreshComputerPad()
    }

    private fun refreshComputerPad() {
        val letter = editorLetter
        padCenter.text = letter?.uppercaseChar()?.toString() ?: "·"
        letterLabel.text = if (letter == null) {
            getString(R.string.computer_key_pick_letter)
        } else {
            getString(R.string.computer_key_letter, letter.uppercaseChar().toString())
        }
        val swipes = letter?.let { editorMap.swipes(it) }.orEmpty()
        for ((direction, button) in padButtons) {
            val label = swipes[direction]?.label ?: "—"
            button.text = "${direction.arrow}\n$label"
        }
    }

    private fun chooseComputerKey(direction: SwipeDirection) {
        val letter = editorLetter
        if (letter == null) {
            Toast.makeText(this, R.string.computer_key_pick_letter, Toast.LENGTH_SHORT).show()
            return
        }
        val keys = ComputerKey.values()
        val items = ArrayList<String>(keys.size + 1)
        items += getString(R.string.computer_key_clear)
        for (key in keys) {
            val owner = editorMap.owner(key)
            val mine = owner != null && owner.letter == letter && owner.direction == direction
            items += if (owner == null || mine) {
                key.label
            } else {
                getString(
                    R.string.computer_key_used,
                    key.label,
                    owner.letter.uppercaseChar().toString(),
                    owner.direction.arrow,
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.computer_key_assign_title,
                    letter.uppercaseChar().toString(),
                    direction.arrow,
                ),
            )
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    updateComputerMap(editorMap.clear(letter, direction))
                    return@setItems
                }
                val key = keys[which - 1]
                val owner = editorMap.owner(key)
                val mine = owner != null && owner.letter == letter && owner.direction == direction
                if (owner != null && !mine) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.computer_key_taken,
                            key.label,
                            owner.letter.uppercaseChar().toString(),
                            owner.direction.arrow,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setItems
                }
                val next = editorMap.assign(letter, direction, key) ?: return@setItems
                updateComputerMap(next)
            }
            .show()
    }

    private fun updateComputerMap(map: ComputerKeyMap) {
        editorMap = map
        keyboardPreferences.setComputerKeyMap(map)
        editorKeyboard.computerMap = map
        refreshComputerPad()
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
