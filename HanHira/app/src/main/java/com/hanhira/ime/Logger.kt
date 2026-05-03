package com.hanhira.ime

import android.util.Log

object Logger {
    private const val TAG = "HanHiraIME"
    var runtimeEnabled: Boolean = false

    private val enabled: Boolean
        get() = BuildConfig.HANHIRA_LOG_ENABLED || runtimeEnabled

    fun d(stage: String, message: String) {
        if (enabled) Log.d(TAG, "[$stage] $message")
    }

    fun w(stage: String, message: String, tr: Throwable? = null) {
        if (enabled) Log.w(TAG, "[$stage] $message", tr)
    }

    fun e(stage: String, message: String, tr: Throwable? = null) {
        Log.e(TAG, "[$stage] $message", tr)
    }
}
