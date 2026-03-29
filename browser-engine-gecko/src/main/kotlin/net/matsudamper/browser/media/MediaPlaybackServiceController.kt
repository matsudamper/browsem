package net.matsudamper.browser.media

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object MediaPlaybackServiceController {
    private val serviceStartRequested = AtomicBoolean(false)

    fun start(context: Context) {
        if (!serviceStartRequested.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        val intent = Intent(appContext, MediaPlaybackService::class.java)
        runCatching {
            appContext.startForegroundService(intent)
        }.onFailure {
            serviceStartRequested.set(false)
            Log.e(TAG, "startForegroundService failed", it)
        }
    }

    fun stop(context: Context) {
        serviceStartRequested.set(false)
        context.applicationContext.stopService(Intent(context, MediaPlaybackService::class.java))
    }

    fun onServiceDestroyed() {
        serviceStartRequested.set(false)
    }

    private const val TAG = "MediaPlaybackCtl"
}
