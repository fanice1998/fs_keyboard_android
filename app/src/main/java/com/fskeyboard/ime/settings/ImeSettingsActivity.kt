package com.fskeyboard.ime.settings

import android.app.Activity
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.fskeyboard.ime.R
import com.fskeyboard.ime.keyboard.KeyboardHeight
import com.fskeyboard.ime.rerank.RerankPreferences
import com.fskeyboard.ime.rerank.onnx.OnnxModelStore

class ImeSettingsActivity : Activity() {
    private lateinit var keyboardPreferences: KeyboardPreferences
    private lateinit var heightSeek: SeekBar
    private lateinit var heightValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ime_settings)
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

        keyboardPreferences = KeyboardPreferences(this)
        heightValue = findViewById(R.id.keyboard_height_value)
        heightSeek = findViewById(R.id.keyboard_height)
        heightSeek.min = KeyboardHeight.MIN_ROW_DP
        heightSeek.max = KeyboardHeight.MAX_ROW_DP
        val saved = keyboardPreferences.rowHeightDp()
        heightSeek.progress = saved
        showHeight(saved)
        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                keyboardPreferences.setRowHeightDp(progress)
                showHeight(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                keyboardPreferences.setRowHeightDp(seekBar.progress, flush = true)
                showHeight(seekBar.progress)
            }
        })
    }

    override fun onPause() {
        if (::keyboardPreferences.isInitialized && ::heightSeek.isInitialized) {
            keyboardPreferences.setRowHeightDp(heightSeek.progress, flush = true)
        }
        super.onPause()
    }

    private fun showHeight(rowDp: Int) {
        heightValue.text = getString(
            R.string.keyboard_height_value,
            KeyboardHeight.percentOfDefault(rowDp),
        )
    }
}
