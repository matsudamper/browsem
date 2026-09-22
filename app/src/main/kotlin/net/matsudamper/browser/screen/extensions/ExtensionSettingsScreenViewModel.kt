package net.matsudamper.browser.screen.extensions

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * 拡張機能の設定ページ (options_ui) を表示するセッションを保持する。
 *
 * 画面回転などの構成変更で作り直されても、入力中の設定を失わないようセッションを開いたまま維持する。
 */
internal class ExtensionSettingsScreenViewModel(
    optionsPageUrl: String,
    runtime: GeckoRuntime,
) : ViewModel() {

    val session: GeckoSession = GeckoSession().also { session ->
        session.open(runtime)
        session.loadUri(optionsPageUrl)
    }

    private val closeTimeoutHandler = Handler(Looper.getMainLooper())

    override fun onCleared() {
        closeSessionAfterStorageFlush()
    }

    /**
     * 拡張機能のページは pagehide で browser.storage.local へ書き込むため、
     * about:blank への遷移が終わってからセッションを閉じる。
     * コンテンツプロセスの死亡などで遷移が完了しない場合に開いたまま残らないよう、
     * 上限時間を過ぎたら閉じる。
     */
    private fun closeSessionAfterStorageFlush() {
        if (!session.isOpen) return
        var closed = false
        val close = {
            if (!closed) {
                closed = true
                closeTimeoutHandler.removeCallbacksAndMessages(null)
                session.progressDelegate = null
                if (session.isOpen) {
                    session.close()
                }
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                close()
            }
        }
        closeTimeoutHandler.postDelayed({ close() }, BLANK_NAVIGATION_CLOSE_TIMEOUT_MS)
        session.loadUri("about:blank")
    }

    private companion object {
        const val BLANK_NAVIGATION_CLOSE_TIMEOUT_MS = 3_000L
    }
}
