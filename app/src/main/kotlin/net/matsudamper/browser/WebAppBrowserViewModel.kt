package net.matsudamper.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.mozilla.geckoview.GeckoRuntime

internal class WebAppBrowserViewModel(
    tabRepository: TabRepository,
    runtime: GeckoRuntime,
    private val mediaWebExtension: MediaWebExtension,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    applicationScope: CoroutineScope,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = null,
        isSinglePage = true,
        persistenceScope = applicationScope,
    )
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    init {
        browserTabController.onTabListChanged = {
            browserSessionLifecycleController.retainOpenersOfLivePopups(
                tabs = browserTabController.tabs,
                selectedTabId = browserTabController.selectedTabId,
            )
        }
        // ページ翻訳ブリッジは content script より先に MessageDelegate が要る。設定画面などで
        // タブの Composition が外れても外さないよう、セッションの寿命に合わせて張る。
        browserTabController.onTabSessionCreated = { session ->
            pageTranslationWebExtension.registerSession(session)
        }
        browserTabController.onTabSessionDisposed = { session ->
            mediaWebExtension.releaseSession(session)
            pageTranslationWebExtension.unregisterSession(session)
        }
    }

    override fun onCleared() {
        browserTabController.close()
    }
}
