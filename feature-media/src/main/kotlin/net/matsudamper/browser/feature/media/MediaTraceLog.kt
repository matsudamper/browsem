package net.matsudamper.browser.feature.media

import android.util.Log

internal object MediaTraceLog {
    private const val TAG = "MediaTrace"

    fun d(message: String) {
        Log.d(TAG, message)
    }
}
