package net.matsudamper.browser.feature.media

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object MediaPlaybackServiceController {
    private val serviceStartRequested = AtomicBoolean(false)

    fun start(context: Context) {
        if (!serviceStartRequested.compareAndSet(false, true)) {
            Log.d(TAG, "start: skip already requested")
            MediaTraceLog.d("CTL start skip alreadyRequested=true")
            return
        }
        val appContext = context.applicationContext
        val intent = Intent(appContext, MediaPlaybackService::class.java)
        Log.d(TAG, "start: request startForegroundService")
        MediaTraceLog.d("CTL start request")
        runCatching {
            appContext.startForegroundService(intent)
        }.onFailure {
            serviceStartRequested.set(false)
            Log.e(TAG, "startForegroundService failed", it)
            MediaTraceLog.d("CTL start failed error=${it.javaClass.simpleName}")
        }
    }

    fun stop(context: Context) {
        serviceStartRequested.set(false)
        Log.d(TAG, "stop: request stopService")
        MediaTraceLog.d("CTL stop request")
        context.applicationContext.stopService(Intent(context, MediaPlaybackService::class.java))
    }

    fun onServiceDestroyed() {
        Log.d(TAG, "onServiceDestroyed")
        MediaTraceLog.d("CTL serviceDestroyed")
        serviceStartRequested.set(false)
    }

    private const val TAG = "MediaPlaybackCtl"
}
