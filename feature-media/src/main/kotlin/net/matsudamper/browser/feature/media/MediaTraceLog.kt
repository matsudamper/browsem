package net.matsudamper.browser.feature.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

object MediaTraceLog {
    private const val TAG = "MediaTrace"
    private var diagnostics: PlaybackDiagnostics? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val flushPending = Runnable { flush(force = false) }

    internal fun install(save: (tag: String, title: String, body: String) -> Unit) {
        diagnostics = PlaybackDiagnostics(save)
    }

    fun d(message: String) {
        Log.d(TAG, message)
        diagnostics?.trace(message, SystemClock.elapsedRealtime())
    }

    internal fun event(kind: String, key: String, details: String) {
        d("$kind $details")
        try {
            diagnostics?.record(kind, key, details, SystemClock.elapsedRealtime())
        } catch (error: RuntimeException) {
            Log.w(TAG, "メディア診断ログの保存に失敗", error)
        }
        if (diagnostics != null && !handler.hasCallbacks(flushPending)) {
            handler.postDelayed(flushPending, PlaybackDiagnostics.REPEAT_INTERVAL_MS)
        }
    }

    internal fun flush() {
        flush(force = true)
    }

    private fun flush(force: Boolean) {
        val delay = try {
            diagnostics?.flush(SystemClock.elapsedRealtime(), force)
            diagnostics?.nextFlushDelayMs(SystemClock.elapsedRealtime())
        } catch (error: RuntimeException) {
            Log.w(TAG, "メディア診断ログの集計保存に失敗", error)
            PlaybackDiagnostics.REPEAT_INTERVAL_MS
        }
        handler.removeCallbacks(flushPending)
        if (delay != null) handler.postDelayed(flushPending, delay)
    }
}
