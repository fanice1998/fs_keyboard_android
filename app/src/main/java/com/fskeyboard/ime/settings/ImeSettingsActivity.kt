package com.fskeyboard.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
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
import com.fskeyboard.ime.ui.AppearanceImageCache
import com.fskeyboard.ime.ui.AppearanceOverrides
import com.fskeyboard.ime.ui.AppearancePipeline
import com.fskeyboard.ime.ui.CandidateChrome
import com.fskeyboard.ime.ui.CopyResult
import com.fskeyboard.ime.ui.KeyAnimation
import com.fskeyboard.ime.ui.KeyboardTheme
import com.fskeyboard.ime.ui.appearanceDir
import com.fskeyboard.ime.ui.appearanceSwatches
import com.fskeyboard.ime.ui.colorsMatch
import com.fskeyboard.ime.ui.copyAppearanceStream
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.editorResolved
import com.fskeyboard.ime.ui.haloColor
import com.fskeyboard.ime.ui.isNightMode
import com.fskeyboard.ime.ui.loadAppearance
import com.fskeyboard.ime.ui.parseHexColor
import com.fskeyboard.ime.ui.resolveKeyboard
import com.fskeyboard.ime.latency.KeyLatency
import com.fskeyboard.ime.rerank.RerankPreferences
import com.fskeyboard.ime.rerank.onnx.OnnxModelStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ImeSettingsActivity : Activity() {
    private lateinit var keyboardPreferences: KeyboardPreferences
    private lateinit var heightSeek: SeekBar
    private lateinit var heightValue: TextView
    private lateinit var strengthSeek: SeekBar
    private lateinit var strengthValue: TextView
    private lateinit var previewKeyboard: KeyboardView
    private lateinit var previewBar: CandidateBarView
    private lateinit var editorKeyboard: KeyboardView
    private lateinit var alphaSeek: SeekBar
    private lateinit var alphaValue: TextView
    private lateinit var backgroundColorButton: Button
    private lateinit var keyFillButton: Button
    private lateinit var keyBorderSwitch: Switch
    private lateinit var keyBorderColorButton: Button
    private lateinit var keyLabelButton: Button
    private lateinit var animationGroup: RadioGroup
    private lateinit var candidateBarButton: Button
    private lateinit var candidateSelectedButton: Button
    private lateinit var candidateUnselectedButton: Button
    private lateinit var candidateTextButton: Button
    private lateinit var imagePick: Button
    private lateinit var imageClear: Button
    private lateinit var contrastNote: TextView
    private var bindingAppearance = false
    private var progressDialog: AlertDialog? = null
    private val copyCancelled = AtomicBoolean(false)
    private val onImageBusy: () -> Unit = { if (::imagePick.isInitialized) imagePick.isEnabled = false }
    private val onImageIdle: () -> Unit = { if (::imagePick.isInitialized) imagePick.isEnabled = true }
    private val previewCandidates = CandidateUiState(
        candidates = listOf(
            Candidate("你", engineIndex = 0),
            Candidate("妳", engineIndex = 1),
            Candidate("泥", engineIndex = 2),
        ),
    )
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
        val zhuyinComputer = findViewById<Switch>(R.id.zhuyin_computer_layout_switch)
        zhuyinComputer.isChecked = keyboardPreferences.zhuyinComputerLayout()
        zhuyinComputer.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setZhuyinComputerLayout(checked)
            previewKeyboard.zhuyinComputerLayout = checked
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
        val inkTheme = findViewById<Switch>(R.id.ink_theme_switch)
        inkTheme.isChecked = keyboardPreferences.keyboardTheme().id == KeyboardTheme.INK
        inkTheme.setOnCheckedChangeListener { _, checked ->
            val theme = if (checked) KeyboardTheme.ink() else KeyboardTheme.paper()
            keyboardPreferences.setKeyboardTheme(theme)
            if (::previewKeyboard.isInitialized) {
                previewKeyboard.theme = theme
                refreshAppearance()
            }
        }
        val keyPreview = findViewById<Switch>(R.id.key_preview_switch)
        keyPreview.isChecked = keyboardPreferences.keyPreviewEnabled()
        keyPreview.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setKeyPreviewEnabled(checked)
            previewKeyboard.keyPreviewEnabled = checked
        }
        val unusedHabit = findViewById<Switch>(R.id.unused_habit_switch)
        unusedHabit.isChecked = keyboardPreferences.forgetUnusedPredictions()
        unusedHabit.setOnCheckedChangeListener { _, checked ->
            keyboardPreferences.setForgetUnusedPredictions(checked)
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
        strengthValue = findViewById(R.id.vibration_strength_value)
        strengthSeek = findViewById(R.id.vibration_strength)
        strengthSeek.min = 0
        strengthSeek.max = KeyboardPreferences.VIBRATION_SCALE_MAX
        val savedStrength = keyboardPreferences.vibrationScale()
        strengthSeek.progress = savedStrength
        showStrength(savedStrength)
        var sampled = savedStrength
        strengthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                previewKeyboard.vibrationScale = progress / 100f
                showStrength(progress)
                if (!fromUser) return
                keyboardPreferences.setVibrationScale(progress)
                if (kotlin.math.abs(progress - sampled) < 8) return
                sampled = progress
                previewKeyboard.playVibrationSample()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                keyboardPreferences.setVibrationScale(seekBar.progress, flush = true)
                previewKeyboard.vibrationScale = seekBar.progress / 100f
                showStrength(seekBar.progress)
                sampled = seekBar.progress
                previewKeyboard.playVibrationSample()
            }
        })
        installAppearance()
    }

    override fun onDestroy() {
        settingsRoot?.viewTreeObserver?.let { observer ->
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(onLayout)
        }
        // The copy thread sees this and abandons the generation only if .ok is not written yet.
        copyCancelled.set(true)
        dismissImageProgress()
        if (AppearancePipeline.onActive === onImageBusy) AppearancePipeline.onActive = null
        if (AppearancePipeline.onIdle === onImageIdle) AppearancePipeline.onIdle = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        showLatency()
        AppearancePipeline.scheduleRecovery(this)
    }

    override fun onPause() {
        if (::keyboardPreferences.isInitialized && ::heightSeek.isInitialized) {
            keyboardPreferences.setRowHeightDp(heightSeek.progress, flush = true)
        }
        if (::keyboardPreferences.isInitialized && ::strengthSeek.isInitialized) {
            keyboardPreferences.setVibrationScale(strengthSeek.progress, flush = true)
        }
        if (::keyboardPreferences.isInitialized && ::alphaSeek.isInitialized &&
            keyboardPreferences.appearance().alphaPercent != null
        ) {
            keyboardPreferences.setKeyboardAlpha(alphaSeek.progress, flush = true)
        }
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BACKGROUND || resultCode != RESULT_OK || isFinishing) return
        val uri = data?.data ?: return
        startBackgroundCopy(uri)
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

    private fun showStrength(percent: Int) {
        strengthValue.text = getString(R.string.vibration_strength_value, percent)
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
        previewBar = bar
        val keyboard = KeyboardView(this).apply {
            rowHeightDp = rowDp
            latinTopNumberRow = keyboardPreferences.latinTopNumberRow()
            zhuyinComputerLayout = keyboardPreferences.zhuyinComputerLayout()
            split = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            computerKeys = keyboardPreferences.computerKeys()
            fullWidthSymbols = keyboardPreferences.fullWidthSymbols()
            vibrationEnabled = keyboardPreferences.vibrationEnabled()
            vibrationScale = keyboardPreferences.vibrationScale() / 100f
            keyPreviewEnabled = keyboardPreferences.keyPreviewEnabled()
            theme = keyboardPreferences.keyboardTheme()
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

    private fun keyFace(token: Char): String = when (token) {
        ComputerKeyMap.DELETE -> "⌫"
        else -> token.uppercaseChar().toString()
    }

    private fun refreshComputerPad() {
        val letter = editorLetter
        padCenter.text = letter?.let { keyFace(it) } ?: "·"
        letterLabel.text = if (letter == null) {
            getString(R.string.computer_key_pick_letter)
        } else {
            getString(R.string.computer_key_letter, keyFace(letter))
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
            val mine = owner != null && owner.letter == letter
            items += if (owner == null || mine) {
                key.label
            } else {
                getString(
                    R.string.computer_key_used,
                    key.label,
                    keyFace(owner.letter),
                    owner.direction.arrow,
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.computer_key_assign_title,
                    keyFace(letter),
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
                val mine = owner != null && owner.letter == letter
                if (owner != null && !mine) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.computer_key_taken,
                            key.label,
                            keyFace(owner.letter),
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

    private fun installAppearance() {
        backgroundColorButton = findViewById(R.id.appearance_background_color)
        keyFillButton = findViewById(R.id.appearance_key_fill)
        keyBorderSwitch = findViewById(R.id.appearance_key_border)
        keyBorderColorButton = findViewById(R.id.appearance_key_border_color)
        keyLabelButton = findViewById(R.id.appearance_key_label)
        animationGroup = findViewById(R.id.appearance_animation)
        candidateBarButton = findViewById(R.id.appearance_candidate_bar)
        candidateSelectedButton = findViewById(R.id.appearance_candidate_selected)
        candidateUnselectedButton = findViewById(R.id.appearance_candidate_unselected)
        candidateTextButton = findViewById(R.id.appearance_candidate_text)
        imagePick = findViewById(R.id.appearance_image_pick)
        imageClear = findViewById(R.id.appearance_image_clear)
        contrastNote = findViewById(R.id.appearance_contrast_note)
        alphaValue = findViewById(R.id.appearance_alpha_value)
        alphaSeek = findViewById(R.id.appearance_alpha)
        alphaSeek.min = 0
        alphaSeek.max = 100
        backgroundColorButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_BACKGROUND_COLOR, backgroundColorButton.text.toString())
        }
        keyFillButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_KEY_FILL_COLOR, keyFillButton.text.toString())
        }
        keyBorderColorButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_KEY_BORDER_COLOR, keyBorderColorButton.text.toString())
        }
        keyLabelButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_KEY_LABEL_COLOR, keyLabelButton.text.toString())
        }
        candidateBarButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_CANDIDATE_BAR, candidateBarButton.text.toString())
        }
        candidateSelectedButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_CANDIDATE_SELECTED, candidateSelectedButton.text.toString())
        }
        candidateUnselectedButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_CANDIDATE_UNSELECTED, candidateUnselectedButton.text.toString())
        }
        candidateTextButton.setOnClickListener {
            chooseAppearanceColor(KeyboardPreferences.KEY_CANDIDATE_TEXT, candidateTextButton.text.toString())
        }
        keyBorderSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingAppearance) return@setOnCheckedChangeListener
            keyboardPreferences.setKeyBorderEnabled(checked)
            refreshAppearance()
        }
        animationGroup.setOnCheckedChangeListener { _, checkedId ->
            if (bindingAppearance) return@setOnCheckedChangeListener
            keyboardPreferences.setKeyAnimation(animationOf(checkedId))
            refreshAppearance()
            previewKeyboard.playAnimationSample()
        }
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                showAlpha(progress)
                if (!fromUser || bindingAppearance) return
                keyboardPreferences.setKeyboardAlpha(progress)
                refreshAppearance()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                keyboardPreferences.setKeyboardAlpha(seekBar.progress, flush = true)
                showAlpha(seekBar.progress)
                refreshAppearance()
            }
        })
        imagePick.setOnClickListener { pickBackground() }
        imagePick.isEnabled = AppearancePipeline.isIdle()
        AppearancePipeline.onActive = onImageBusy
        AppearancePipeline.onIdle = onImageIdle
        imageClear.setOnClickListener { clearBackgroundImage() }
        findViewById<Button>(R.id.appearance_reset).setOnClickListener { resetAppearance() }
        refreshAppearance()
    }

    private fun showAlpha(percent: Int) {
        alphaValue.text = getString(R.string.appearance_alpha_value, percent)
    }

    private fun refreshAppearance() {
        if (!::previewKeyboard.isInitialized || !::previewBar.isInitialized) return
        val loaded = loadAppearance(this)
        val night = isNightMode()
        val theme = keyboardPreferences.keyboardTheme()
        val resolved = resolveKeyboard(theme, night, loaded, appearanceDir(this))
        val chrome = CandidateChrome.resolve(night, loaded)
        previewKeyboard.applyAppearance(resolved, night)
        val image = resolved.imageFile?.takeIf { it.isFile }
        if (image != null) {
            AppearanceImageCache.attach(previewKeyboard)
            AppearancePipeline.ensureLoaded(image)
        } else {
            AppearanceImageCache.detach(previewKeyboard)
            previewKeyboard.setBackgroundBitmap(null, Long.MIN_VALUE)
        }
        previewBar.render(previewCandidates, chrome)
        refreshEditor(loaded, night)
        if (!::alphaSeek.isInitialized) return
        bindingAppearance = true
        val percent = loaded.alphaPercent ?: 100
        alphaSeek.progress = percent
        showAlpha(percent)
        keyBorderSwitch.isChecked = loaded.keyBorderEnabled ?: (theme.borderDp > 0)
        animationGroup.check(animationId(loaded.animation ?: KeyAnimation.NONE))
        paintSwatch(backgroundColorButton, resolved.palette.background)
        paintSwatch(keyFillButton, resolved.palette.key)
        paintSwatch(keyBorderColorButton, resolved.borderColor)
        paintSwatch(keyLabelButton, resolved.labelColor)
        paintSwatch(candidateBarButton, chrome.bar)
        paintSwatch(candidateSelectedButton, chrome.selected)
        paintSwatch(candidateUnselectedButton, chrome.unselected)
        paintSwatch(candidateTextButton, chrome.text)
        imageClear.visibility = if (loaded.imageName != null) View.VISIBLE else View.GONE
        val lowContrast = colorsMatch(resolved.labelColor, resolved.palette.key) ||
            colorsMatch(chrome.text, chrome.unselected)
        contrastNote.visibility = if (lowContrast) View.VISIBLE else View.GONE
        bindingAppearance = false
    }

    /** Paper until a key fill, border, label, or animation key exists. No theme lookup here. */
    private fun refreshEditor(overrides: AppearanceOverrides, night: Boolean) {
        if (!::editorKeyboard.isInitialized) return
        val resolved = editorResolved(overrides, night)
        if (resolved == null) editorKeyboard.clearAppearance() else editorKeyboard.applyAppearance(resolved, night)
    }

    private fun paintSwatch(button: Button, color: Int) {
        button.setBackgroundColor(color)
        button.setTextColor(haloColor(color))
    }

    private fun chooseAppearanceColor(key: String, title: String) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        val grid = GridLayout(this).apply { columnCount = 6 }
        val hex = EditText(this).apply {
            hint = getString(R.string.appearance_hex_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        lateinit var dialog: AlertDialog
        appearanceSwatches().forEachIndexed { index, color ->
            val swatch = View(this).apply {
                setBackgroundColor(color)
                setOnClickListener {
                    keyboardPreferences.setAppearanceColor(key, color)
                    dialog.dismiss()
                    refreshAppearance()
                }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 6),
                GridLayout.spec(index % 6, 1f),
            )
            params.width = 0
            params.height = dp(36)
            params.setMargins(dp(4), dp(4), dp(4), dp(4))
            grid.addView(swatch, params)
        }
        column.addView(grid)
        column.addView(hex)
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(column)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseHexColor(hex.text?.toString().orEmpty()) ?: return@setOnClickListener
                keyboardPreferences.setAppearanceColor(key, parsed)
                dialog.dismiss()
                refreshAppearance()
            }
        }
        dialog.show()
    }

    private fun animationOf(checkedId: Int): KeyAnimation = when (checkedId) {
        R.id.appearance_animation_pop -> KeyAnimation.POP
        R.id.appearance_animation_ripple -> KeyAnimation.RIPPLE
        R.id.appearance_animation_flash -> KeyAnimation.FLASH
        R.id.appearance_animation_sink -> KeyAnimation.SINK
        else -> KeyAnimation.NONE
    }

    private fun animationId(animation: KeyAnimation): Int = when (animation) {
        KeyAnimation.NONE -> R.id.appearance_animation_none
        KeyAnimation.POP -> R.id.appearance_animation_pop
        KeyAnimation.RIPPLE -> R.id.appearance_animation_ripple
        KeyAnimation.FLASH -> R.id.appearance_animation_flash
        KeyAnimation.SINK -> R.id.appearance_animation_sink
    }

    @Suppress("DEPRECATION")
    private fun pickBackground() {
        if (!AppearancePipeline.isIdle()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_BACKGROUND)
    }

    private fun startBackgroundCopy(uri: android.net.Uri) {
        if (!AppearancePipeline.isIdle() || isFinishing) return
        val gen = System.currentTimeMillis()
        copyCancelled.set(false)
        AppearancePipeline.beginGeneration(gen)
        val dir = appearanceDir(this)
        val incoming = File(dir, "incoming-$gen")
        val ok = File(dir, "incoming-$gen.ok")
        showImageProgress()
        Thread({
            val result = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    copyAppearanceStream(input, incoming, ok, copyCancelled)
                } ?: CopyResult.CANCELLED
            } catch (_: Exception) {
                CopyResult.CANCELLED
            }
            if (result != CopyResult.COPIED) {
                incoming.delete()
                ok.delete()
                File(dir, "abandoned-$gen").apply {
                    parentFile?.mkdirs()
                    if (!exists()) createNewFile()
                }
            }
            runOnUiThread {
                if (result == CopyResult.COPIED) {
                    dismissImageProgress()
                    AppearancePipeline.finish(gen, applicationContext)
                } else {
                    if (result == CopyResult.TOO_LARGE && !isDestroyed) {
                        Toast.makeText(this, R.string.appearance_image_too_large, Toast.LENGTH_SHORT).show()
                    }
                    dismissImageProgress()
                    AppearancePipeline.dropGeneration(gen, dir)
                }
            }
        }, "fskeyboard-appearance-copy").start()
    }

    private fun clearBackgroundImage() {
        copyCancelled.set(true)
        dismissImageProgress()
        AppearancePipeline.requestOfficialDelete(appearanceDir(this))
        keyboardPreferences.setBackgroundImageName(null, flush = true)
        refreshAppearance()
    }

    private fun resetAppearance() {
        copyCancelled.set(true)
        dismissImageProgress()
        AppearancePipeline.requestOfficialDelete(appearanceDir(this))
        keyboardPreferences.clearAppearance(KeyboardPreferences.APPEARANCE_KEYS, flush = true)
        refreshAppearance()
    }

    private fun showImageProgress() {
        if (progressDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.appearance_image_reading)
            .setCancelable(false)
            .create()
        progressDialog = dialog
        dialog.show()
    }

    private fun dismissImageProgress() {
        val dialog = progressDialog ?: return
        progressDialog = null
        if (dialog.isShowing) dialog.dismiss()
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

    private companion object {
        const val REQUEST_BACKGROUND = 41
    }
}
