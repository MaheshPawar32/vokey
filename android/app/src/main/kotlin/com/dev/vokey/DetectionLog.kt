package com.dev.vokey

import android.util.Log

/**
 * Opt-in detector diagnostics for tuning on a real phone. Off by default;
 * enable with:  adb shell setprop log.tag.VokeyDetect DEBUG
 * Logs numeric features only (levels, ratios) — never audio.
 */
internal object DetectionLog {
    private const val TAG = "VokeyDetect"

    fun sinkOrNull(): ((String) -> Unit)? =
        if (Log.isLoggable(TAG, Log.DEBUG)) { message -> Log.d(TAG, message) } else null
}
