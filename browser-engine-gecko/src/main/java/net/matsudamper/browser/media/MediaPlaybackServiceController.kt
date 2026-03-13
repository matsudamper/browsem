package net.matsudamper.browser.media

import android.content.Context
import android.content.Intent
import android.os.Build
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            runCatching {
                appContext.startService(intent)
            }.onFailure {
                serviceStartRequested.set(false)
                Log.e(TAG, "startService failed", it)
            }
            return
        }

        // バックグラウンド制約にかかった場合のみ foreground 起動へフォールバックする。
        runCatching {
            appContext.startService(intent)
        }.onFailure {
            Log.w(TAG, "startService failed, fallback to startForegroundService", it)
            intent.putExtra(MediaPlaybackService.EXTRA_REQUIRE_IMMEDIATE_FOREGROUND, true)
            runCatching {
                appContext.startForegroundService(intent)
            }.onFailure { startForegroundError ->
                serviceStartRequested.set(false)
                Log.e(TAG, "startForegroundService failed", startForegroundError)
            }
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
