package com.hanpin.ime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.getSystemService
import com.google.android.material.button.MaterialButton

class MainActivity : ComponentActivity() {
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
            Logger.i("SETUP", "Open input method settings")
            openImeSettingsSafely()
        }

        pickerButton.setOnClickListener {
            Logger.i("SETUP", "Show picker requested")
            showPickerOrGuide()
        }
    }

    override fun onResume() {
        super.onResume()
        updateSetupState()
    }

    private fun openImeSettingsSafely() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            toast(getString(R.string.setup_settings_fallback))
        }
    }

    private fun showPickerOrGuide() {
        val imm = getSystemService<InputMethodManager>() ?: run {
            toast(getString(R.string.setup_picker_unavailable))
            return
        }
        if (!isHanPinEnabled(imm)) {
            openImeSettingsSafely()
            toast(getString(R.string.setup_enable_first))
            return
        }
        imm.showInputMethodPicker()
        toast(getString(R.string.setup_picker_hint))
    }

    private fun updateSetupState() {
        val imm = getSystemService<InputMethodManager>()
        val enabled = imm?.let { isHanPinEnabled(it) } ?: false
        val selected = isHanPinDefault()

        statusText.text = when {
            selected -> getString(R.string.setup_state_selected)
            enabled -> getString(R.string.setup_state_enabled)
            else -> getString(R.string.setup_state_disabled)
        }

        pickerButton.isEnabled = enabled
        pickerButton.alpha = if (enabled) 1f else 0.6f
        pickerButton.text = if (enabled) {
            getString(R.string.show_picker_banner)
        } else {
            getString(R.string.show_picker_banner_disabled)
        }
    }

    private fun isHanPinEnabled(imm: InputMethodManager): Boolean {
        val serviceName = "$packageName/.HanPinIME"
        return imm.enabledInputMethodList.any { info ->
            info.packageName == packageName || info.id == serviceName || info.id.contains("$packageName/")
        }
    }

    private fun isHanPinDefault(): Boolean {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return current?.contains("$packageName/") == true
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
