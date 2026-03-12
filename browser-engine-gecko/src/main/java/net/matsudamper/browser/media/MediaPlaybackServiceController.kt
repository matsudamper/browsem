package net.matsudamper.browser.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

internal object MediaPlaybackServiceController {

    fun start(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, MediaPlaybackService::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            appContext.startService(intent)
            return
        }

        // バックグラウンド制約にかかった場合のみ foreground 起動へフォールバックする。
        runCatching {
            appContext.startService(intent)
        }.onFailure {
            Log.w(TAG, "startService failed, fallback to startForegroundService", it)
            intent.putExtra(MediaPlaybackService.EXTRA_REQUIRE_IMMEDIATE_FOREGROUND, true)
            appContext.startForegroundService(intent)
        }
    }

    fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context, MediaPlaybackService::class.java))
    }

    private const val TAG = "MediaPlaybackCtl"
}
