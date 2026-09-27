package net.matsudamper.browser

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.WebAppId
import net.matsudamper.browser.data.WebAppRepository
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.mozilla.geckoview.GeckoRuntime

internal class WebAppBrowserViewModel(
    tabRepository: TabRepository,
    private val webAppRepository: WebAppRepository,
    runtime: GeckoRuntime,
    private val mediaWebExtension: MediaWebExtension,
    private val pageTranslationWebExtension: PageTranslationWebExtension,
    applicationScope: CoroutineScope,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = null,
        profileRepository = null,
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

    /**
     * ウェブアプリの唯一のタブを作る。
     * 登録済みのウェブアプリはプロセス終了後に Activity が再生成されたとき復元できるよう、sessionState を保存し続ける。
     */
    suspend fun createTab(
        initialUrl: String,
        profileId: ProfileId,
        webAppId: WebAppId?,
        restoredSessionState: String?,
    ): BrowserTab {
        val tab = browserTabController.createAndAppendTab(
            initialUrl = initialUrl,
            restoredSessionState = restoredSessionState,
            profileId = profileId,
        )
        if (webAppId != null) {
            viewModelScope.launch {
                snapshotFlow { tab.sessionState }.collect { sessionState ->
                    webAppRepository.saveSessionState(webAppId, sessionState)
                }
            }
        }
        return tab
    }

    override fun onCleared() {
        browserTabController.close()
    }
}
