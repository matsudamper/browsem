package net.matsudamper.browser

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.ProfileId
import net.matsudamper.browser.data.ProfileRepository
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.WebAppData
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
    private val webAppShortcutManager: WebAppShortcutManager,
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

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

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
            val migratedLaunchInfo = migrateLegacyShortcut(launchRequest.target)
            if (migratedLaunchInfo == null) {
                webAppTabFlow.value = createWebAppTab(launchRequest)
            } else {
                eventHandler.trySend { it.relaunch(migratedLaunchInfo) }
            }
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
     * バックアップの復元などで登録だけ消えたアプリは、ホームのアイコンが残っていれば作り直す。
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
                val webApp = webAppRepository.getWebApp(target.webAppId) ?: restoreFromPinnedShortcut(target)
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
     * 登録の無い ID で起動されたとき、同じ起動 URI のアイコンがホームにあれば、その情報から同じ ID で登録を作り直す。
     * ピン留めされたアイコン以外（他アプリからの Intent 等）から起動したときは作らない。
     */
    private suspend fun restoreFromPinnedShortcut(target: WebAppLaunchTarget.Registered): WebAppData? {
        val pageUrl = ExternalInitialUrlPolicy.sanitize(target.pageUrl) ?: return null
        val shortcut = withContext(Dispatchers.IO) {
            webAppShortcutManager.findPinnedShortcut(target.launchUri)
        } ?: return null
        return webAppRepository.restoreWebApp(
            webAppId = target.webAppId,
            profileId = target.profileId,
            startUrl = pageUrl,
            title = shortcut.label,
        )
    }

    /**
     * 旧形式のアイコンから起動したとき、DB に登録してアイコンを新形式へ書き換え、移行後の起動情報を返す。
     * ピン留めされたアイコン以外（他アプリからの Intent 等）から起動したときは登録しない。
     */
    private suspend fun migrateLegacyShortcut(target: WebAppLaunchTarget?): WebAppLaunchInfo? {
        if (target !is WebAppLaunchTarget.Legacy) return null
        val pageUrl = ExternalInitialUrlPolicy.sanitize(target.pageUrl) ?: return null
        val shortcut = withContext(Dispatchers.IO) {
            webAppShortcutManager.findPinnedShortcut(target.launchUri)
        } ?: return null
        val webAppId = webAppRepository.addWebApp(
            profileId = target.profileId,
            startUrl = pageUrl,
            title = shortcut.label,
        )
        val launchInfo = WebAppLaunchInfo(webAppId = webAppId, startUrl = pageUrl, profileId = target.profileId)
        // アイコンが旧形式のままだと次回の起動でまた別の登録を作ってしまうため、書き換えられなければ登録を取り消す
        val isMigrated = withContext(Dispatchers.IO) {
            webAppShortcutManager.migrateLegacyShortcut(shortcut, launchInfo)
        }
        if (!isMigrated) {
            webAppRepository.deleteWebApp(webAppId)
            return null
        }
        return launchInfo
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

    interface Event {
        /**
         * 旧形式から移行したので、新しい起動 URI のタスクで開き直す。
         * documentLaunchMode はタスクを起動 URI で照合するため、旧 URI のタスクのままだと
         * 書き換え後のアイコンから開いたときに同じアプリのタスクがもう一つできる。
         */
        fun relaunch(launchInfo: WebAppLaunchInfo)
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
