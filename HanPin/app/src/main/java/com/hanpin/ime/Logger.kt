package com.hanpin.ime

import android.util.Log

object Logger {
    var enabled: Boolean = BuildConfig.HANPIN_LOG_ENABLED
    var logInput: Boolean = true
    var logCompose: Boolean = true
    var logCommit: Boolean = true
    var logCandidates: Boolean = true

    private const val TAG = "HanPin"

    fun input(message: String) {
        if (enabled && logInput) Log.d(TAG, "[INPUT] $message")
    }

    fun compose(message: String) {
        if (enabled && logCompose) Log.d(TAG, "[COMPOSE] $message")
    }

    fun commit(message: String) {
        if (enabled && logCommit) Log.d(TAG, "[COMMIT] $message")
    }

    fun candidate(message: String) {
        if (enabled && logCandidates) Log.d(TAG, "[CANDIDATE] $message")
    }

    fun i(section: String, message: String) {
        if (enabled) Log.d(TAG, "[$section] $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (enabled) Log.e(TAG, message, throwable)
    }
}
