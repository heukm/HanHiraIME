package com.hanhira.ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var enableButton: MaterialButton
    private lateinit var pickerButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        enableButton = findViewById(R.id.enableImeBanner)
        pickerButton = findViewById(R.id.showPickerBanner)

        enableButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }.onFailure {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        pickerButton.setOnClickListener {
            if (!isImeEnabled()) {
                statusText.text = getString(R.string.setup_enable_first)
                return@setOnClickListener
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            runCatching {
                imm.showInputMethodPicker()
                statusText.text = getString(R.string.setup_picker_hint)
            }.onFailure {
                statusText.text = getString(R.string.setup_picker_unavailable)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        CandidateEngine.get(this).preload()
    }

    private fun refreshStatus() {
        when {
            isImeSelected() -> {
                statusText.text = getString(R.string.setup_state_selected)
                pickerButton.text = getString(R.string.show_picker_banner)
                pickerButton.isEnabled = true
            }
            isImeEnabled() -> {
                statusText.text = getString(R.string.setup_state_enabled)
                pickerButton.text = getString(R.string.show_picker_banner)
                pickerButton.isEnabled = true
            }
            else -> {
                statusText.text = getString(R.string.setup_state_disabled)
                pickerButton.text = getString(R.string.show_picker_banner_disabled)
                pickerButton.isEnabled = true
            }
        }
    }

    private fun isImeEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        return enabled.contains(packageName)
    }

    private fun isImeSelected(): Boolean {
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        return selected.contains(packageName)
    }
}
