package net.matsudamper.browser

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * メインスレッドの応答性を監視し、デッドロック検知時にプロセスをキルして復旧する。
 *
 * GeckoView の子プロセス（拡張プロセス等）が OS にキルされた場合、
 * Gecko エンジン内部でメインスレッドがデッドロックし、スプラッシュ画面のまま
 * 完全にフリーズすることがある。
 * 定期的にメインスレッドへハートビートを投稿し、一定時間応答がなければ
 * プロセスをキルして Android に再起動させる。
 */
internal class MainThreadWatchdog {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var heartbeatAcknowledged = true

    fun start() {
        val thread = HandlerThread("MainThreadWatchdog").apply {
            isDaemon = true
            start()
        }
        val watchdogHandler = Handler(thread.looper)
        watchdogHandler.postDelayed(
            { runCheck(watchdogHandler) },
            STARTUP_GRACE_MS,
        )
    }

    private fun runCheck(watchdogHandler: Handler) {
        heartbeatAcknowledged = false
        mainHandler.post { heartbeatAcknowledged = true }
        watchdogHandler.postDelayed({
            if (!heartbeatAcknowledged && !Debug.isDebuggerConnected()) {
                Log.e(
                    TAG,
                    "メインスレッドが ${TIMEOUT_MS}ms 応答なし。プロセスを再起動します",
                )
                Process.killProcess(Process.myPid())
                return@postDelayed
            }
            runCheck(watchdogHandler)
        }, TIMEOUT_MS)
    }

    private companion object {
        private const val TAG = "MainThreadWatchdog"
        private const val TIMEOUT_MS = 8_000L

        // 起動直後は GeckoRuntime.create() 等でメインスレッドが占有されるため猶予を設ける
        private const val STARTUP_GRACE_MS = 15_000L
    }
}
