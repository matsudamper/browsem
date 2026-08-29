package net.matsudamper.browser

import android.content.Context
import android.util.Log
import net.matsudamper.browser.data.crashlog.CrashLogRepository

/**
 * アプリ内の未捕捉例外を Room に保存してから、既定のハンドラへ委譲する。
 * Native クラッシュは対象外。
 */
class CrashLogExceptionHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            CrashLogRepository(context).saveCrashSync(thread, throwable)
        } catch (error: Exception) {
            Log.e(TAG, "クラッシュログの保存に失敗", error)
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val TAG = "CrashLogExceptionHandler"

        fun install(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (defaultHandler is CrashLogExceptionHandler) return
            Thread.setDefaultUncaughtExceptionHandler(
                CrashLogExceptionHandler(context.applicationContext, defaultHandler),
            )
        }
    }
}
