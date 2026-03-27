package net.matsudamper.browser

import net.matsudamper.browser.media.MediaWebExtension
import org.mozilla.geckoview.GeckoRuntime

/**
 * GeckoRuntime に関連するコンポーネント（セッション管理・拡張機能）を束ねるクラス。
 *
 * 拡張機能（ThemeColorWebExtension, MediaWebExtension）はプロセスに1つの GeckoRuntime に
 * 対してインストールされるため、Koin の single で管理し、外部から注入する。
 * BrowserTabController / BrowserSessionLifecycleController は Activity ごとに独立して生成される。
 */
class BrowserRuntimeCoordinator(
    val runtime: GeckoRuntime,
    val themeColorExtension: ThemeColorWebExtension,
    val mediaWebExtension: MediaWebExtension,
    tabRepository: net.matsudamper.browser.data.TabRepository,
) {
    val browserTabController = BrowserTabController(tabRepository)
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)
    val browserSessionController = BrowserSessionController(browserTabController)

    fun applyRuntimeSettings(enableThirdPartyCa: Boolean) {
        runtime.settings.setEnterpriseRootsEnabled(enableThirdPartyCa)
    }

    /**
     * この Coordinator が管理するセッションを閉じる。
     * 拡張機能はプロセススコープで管理されるため、ここでは解放しない。
     */
    fun close() {
        browserTabController.close()
    }
}
