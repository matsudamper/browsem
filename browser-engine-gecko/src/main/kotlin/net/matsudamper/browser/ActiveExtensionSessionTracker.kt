package net.matsudamper.browser

import androidx.annotation.VisibleForTesting
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

/**
 * 拡張機能 (AdGuard 等) に「現在アクティブなタブ」を通知するための追跡。
 *
 * `WebExtensionController.setTabActive(session, true)` は同時に一つの session のみ active として
 * 扱うのが意図された使い方なので、直前の active session を覚えておき、切り替え時に false → true の
 * 順で更新する。webRequest 等が tabId を参照して動作する拡張は、active タブが分からないと blocking を
 * スキップすることがある。
 *
 * GeckoRuntime はプロセスに 1 つで、カスタムタブや WebApp は画面ごとに別の
 * [BrowserSessionLifecycleController] を持つ。画面ごとに覚えると他画面の session を false に
 * できず active が複数になるため、プロセス単位で追跡する。
 */
internal object ActiveExtensionSessionTracker {
    private var activeSession: GeckoSession? = null

    fun markActive(runtime: GeckoRuntime, session: GeckoSession) {
        if (!session.isOpen) return
        if (activeSession === session) return
        val previous = activeSession
        if (previous != null && previous !== session && previous.isOpen) {
            runtime.webExtensionController.setTabActive(previous, false)
        }
        runtime.webExtensionController.setTabActive(session, true)
        activeSession = session
    }

    @VisibleForTesting
    fun resetForTesting() {
        activeSession = null
    }
}
