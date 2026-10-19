package com.fskeyboard.ime.settings

import android.app.Activity
import android.os.Bundle
import android.widget.Switch
import android.widget.TextView
import com.fskeyboard.ime.R
import com.fskeyboard.ime.rerank.RerankPreferences
import com.fskeyboard.ime.rerank.onnx.OnnxModelStore

class ImeSettingsActivity : Activity() {
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
    }
}
