package net.matsudamper.browser

import org.mozilla.geckoview.GeckoSession

/**
 * BrowserTab 化される前のセッションをランタイム設定変更時の再読み込み対象へ登録する。
 */
fun registerBrowserSessionForRuntimeUpdates(session: GeckoSession) {
    BrowserSessionRegistry.register(session)
}
