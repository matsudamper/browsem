package net.matsudamper.browser

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.WebAppId
import net.matsudamper.browser.data.WebAppRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.translate.PageTranslationWebExtension
import org.mozilla.geckoview.GeckoRuntime

internal class WebAppBrowserViewModel(
    launchRequest: WebAppLaunchRequest,
    tabRepository: TabRepository,
    private val webAppRepository: WebAppRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
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

    private val webAppTabFlow = MutableStateFlow<WebAppTab?>(null)

    /** ウェブアプリの唯一のタブ。起動先の解決とタブ作成が終わるまでは null */
    val webAppTab: StateFlow<WebAppTab?> = webAppTabFlow.asStateFlow()

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
        // Activity再生成（フォルダブル開閉等）では ViewModel ごとタブが残るため、タブの作成はここで一度だけ行う。
        // タブの破棄は onCleared() で行う。
        viewModelScope.launch {
            webAppTabFlow.value = createWebAppTab(launchRequest)
        }
    }

    override fun onCleared() {
        browserTabController.close()
    }

    /**
     * 起動先を解決してタブを作る。
     * 登録済みのウェブアプリはプロセス終了後に Activity が再生成されたとき復元できるよう、sessionState を保存し続ける。
     */
    private suspend fun createWebAppTab(launchRequest: WebAppLaunchRequest): WebAppTab {
        val destination = resolveLaunchDestination(launchRequest)
        val startUrl = destination.pageUrl ?: settingsRepository.settings.first().resolvedHomepageUrl()
        val tab = browserTabController.createAndAppendTab(
            initialUrl = startUrl,
            restoredSessionState = destination.restoredSessionState,
            profileId = resolveRegisteredProfileId(destination.profileId),
        )
        val webAppId = destination.webAppId
        if (webAppId != null) {
            viewModelScope.launch {
                snapshotFlow { tab.sessionState }.collect { sessionState ->
                    webAppRepository.saveSessionState(webAppId, sessionState)
                }
            }
        }
        return WebAppTab(browserTab = tab, startUrl = startUrl)
    }

    /**
     * 起動するページとプロファイルを解決する。
     * ページ URL は http/https の場合のみ採用し、それ以外は null にしてホームページにフォールバックさせる。
     * 削除済みのウェブアプリもホームページにフォールバックさせる。
     */
    private suspend fun resolveLaunchDestination(launchRequest: WebAppLaunchRequest): LaunchDestination {
        return when (val target = launchRequest.target) {
            null -> LaunchDestination.homepage()

            is WebAppLaunchTarget.Legacy -> LaunchDestination(
                pageUrl = ExternalInitialUrlPolicy.sanitize(target.pageUrl),
                profileId = target.profileId,
                webAppId = null,
                restoredSessionState = null,
            )

            is WebAppLaunchTarget.Registered -> {
                val webApp = webAppRepository.getWebApp(target.webAppId)
                if (webApp == null) {
                    LaunchDestination.homepage()
                } else {
                    LaunchDestination(
                        pageUrl = ExternalInitialUrlPolicy.sanitize(webApp.startUrl),
                        profileId = webApp.profileId,
                        webAppId = webApp.id,
                        restoredSessionState = if (launchRequest.restoresSession) {
                            webAppRepository.loadSessionState(webApp.id)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    /**
     * exported な Activity は他アプリから任意のプロファイル ID を渡され得るうえ、削除済みプロファイルのアプリも残るため、
     * 登録済みでないプロファイルはデフォルトプロファイルにフォールバックさせる。
     */
    private suspend fun resolveRegisteredProfileId(profileId: ProfileId): ProfileId {
        if (profileId == ProfileId.DEFAULT) return profileId
        val isRegistered = profileRepository.observeProfiles().first().any { it.id == profileId }
        return if (isRegistered) profileId else ProfileId.DEFAULT
    }

    private data class LaunchDestination(
        val pageUrl: String?,
        val profileId: ProfileId,
        val webAppId: WebAppId?,
        val restoredSessionState: String?,
    ) {
        companion object {
            fun homepage(): LaunchDestination = LaunchDestination(
                pageUrl = null,
                profileId = ProfileId.DEFAULT,
                webAppId = null,
                restoredSessionState = null,
            )
        }
    }
}

/**
 * @param restoresSession プロセス終了後に Recents から Activity が再生成されたときだけ true にし、前回のページ状態を復元する
 */
internal data class WebAppLaunchRequest(
    val target: WebAppLaunchTarget?,
    val restoresSession: Boolean,
)

internal data class WebAppTab(
    val browserTab: BrowserTab,
    val startUrl: String,
)
