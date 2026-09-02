package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.matsudamper.browser.core.ExternalDownloadTabNavigationPolicy
import net.matsudamper.browser.data.ResolvedBrowserSettings
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.resolvedBrowserSettings
import net.matsudamper.browser.data.resolvedInputAutoZoomEnabled
import net.matsudamper.browser.feature.media.MediaWebExtension
import net.matsudamper.browser.feature.mocklocation.MockLocationWebExtension
import net.matsudamper.browser.feature.themecolor.ThemeColorWebExtension
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal data class BrowserAppUiState(
    val themeMode: ThemeMode,
    val homepageUrl: String,
    val searchTemplate: String,
    val translationProvider: TranslationProvider,
)

private data class BrowserLogicSettings(
    val themeMode: ThemeMode,
    val homepageUrl: String,
    val searchTemplate: String,
    val translationProvider: TranslationProvider,
    val enableThirdPartyCa: Boolean,
)

private data class ViewModelState(
    val logicSettings: BrowserLogicSettings? = null,
)

internal data class ExternalTabFinishCleanup(
    val tabId: String,
    val nextSelectedTabId: String?,
)

@Stable
internal class BrowserViewModel(
    val runtime: GeckoRuntime,
    val themeColorExtension: ThemeColorWebExtension,
    val mediaWebExtension: MediaWebExtension,
    private val settingsRepository: SettingsRepository,
    private val tabRepository: TabRepository,
    private val tabGroupRepository: TabGroupRepository,
    private val mockLocationWebExtension: MockLocationWebExtension,
    private val siteSettingsRepository: SiteSettingsRepository,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = tabGroupRepository,
        isSinglePage = false,
    )
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    init {
        browserTabController.onTabListChanged = {
            browserSessionLifecycleController.retainOpenersOfLivePopups(
                tabs = browserTabController.tabs,
                selectedTabId = browserTabController.selectedTabId,
            )
        }
    }

    // タブ復元完了シグナル。BrowserTabController が内部で管理し、構成変更後も有効。
    val setupComplete: Deferred<Unit> get() = browserTabController.restoreComplete

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        fun onTabsRestored(tabId: String)
    }

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())
    val uiState: StateFlow<BrowserAppUiState?> = MutableStateFlow<BrowserAppUiState?>(null)
        .also { uiStateFlow ->
            viewModelScope.launch {
                viewModelStateFlow.collectLatest { state ->
                    uiStateFlow.update { state.logicSettings?.toBrowserAppUiState() }
                }
            }
        }.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                viewModelStateFlow.update {
                    it.copy(logicSettings = settings.resolvedBrowserSettings().toLogicSettings())
                }
            }
        }
        viewModelScope.launch {
            viewModelStateFlow
                .map { state -> state.logicSettings?.enableThirdPartyCa }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { enableThirdPartyCa ->
                    runtime.settings.setEnterpriseRootsEnabled(enableThirdPartyCa)
                }
        }
        viewModelScope.launch {
            settingsRepository.settings
                .map { settings -> settings.resolvedInputAutoZoomEnabled() }
                .distinctUntilChanged()
                .collect { inputAutoZoomEnabled ->
                    runtime.settings.setInputAutoZoomEnabled(inputAutoZoomEnabled)
                }
        }
        // アプリ起動時に位置情報設定（モック座標・サイトごとの扱い）を拡張機能へ反映する。
        // 設定画面を開かなくても前回セッションの設定が即座に有効になる。
        viewModelScope.launch {
            combine(
                settingsRepository.settings,
                siteSettingsRepository.geolocationStates(),
            ) { settings, geolocationStates -> settings to geolocationStates }
                .collectLatest { (settings, geolocationStates) ->
                    val lat = settings.mockLocationLatitude
                    val lng = settings.mockLocationLongitude
                    val resolvedLat = if (lat == 0.0 && lng == 0.0) MockLocationWebExtension.DEFAULT_LATITUDE else lat
                    val resolvedLng = if (lat == 0.0 && lng == 0.0) MockLocationWebExtension.DEFAULT_LONGITUDE else lng
                    mockLocationWebExtension.updateConfig(
                        MockLocationWebExtension.GeolocationConfig(
                            latitude = resolvedLat,
                            longitude = resolvedLng,
                            siteModes = geolocationStates.mapValues { (_, state) -> state.toGeolocationMode() },
                        ),
                    )
                }
        }
        // ページが位置情報を要求したら記録し、「サイトの設定」画面に位置情報の項目を表示できるようにする
        mockLocationWebExtension.onGeolocationRequested = { host ->
            viewModelScope.launch {
                siteSettingsRepository.markGeolocationRequested(host)
            }
        }
        // ViewModel 生成時にタブ復元を開始する。
        // バックスタックの状態に依存せず、復元は必ず実行される。
        viewModelScope.launch {
            val tabId = restoreTabs()
            // 初回起動時にタブ件数表示が空になるのを防ぐため、タブ一覧画面を開く前に
            // デフォルトグループを作成し、復元済みタブを割り当てておく。
            // TabsScreenViewModel でも同様の初期化を行うが、最初に表示されるのは
            // BrowserScreen であり、グループ割当が無いと groupTabCount が null になる。
            tabGroupRepository.createDefaultGroupIfEmpty(
                browserTabController.tabs.map { it.tabId },
            )
            eventHandler.trySend { it.onTabsRestored(tabId) }
        }
    }

    /**
     * 拡張機能 (AdGuard 等) から chrome.tabs.create / onOpenOptionsPage で要求された
     * 新タブを生成する。
     *
     * ユーザーが「デフォルト」指定したタブグループ (isDefault=true) があれば、
     * createAndAppendTabWithSession より先に DB へグループ割当を書き込む。
     * これをしないと TabsScreenViewModel のウォッチャーが先に発火して
     * アクティブグループ (最初のグループになりがち) へ自動割当されてしまう。
     * 外部 Intent からのタブ作成 (BrowserApp.kt) と同じ手順。
     */
    suspend fun createExtensionRequestedTab(
        url: String,
        active: Boolean,
    ): GeckoSession {
        val tabId = UUID.randomUUID().toString()
        val defaultGroupId = tabGroupRepository.getDefaultGroupId()
        if (defaultGroupId != null) {
            tabGroupRepository.assignTabToGroup(tabId, defaultGroupId)
        }
        return withContext(Dispatchers.Main) {
            val session = GeckoSession()
            val newTab = browserTabController.createAndAppendTabWithSession(
                session = session,
                tabId = tabId,
                initialUrl = url,
            )
            if (active) {
                browserTabController.selectTab(newTab.tabId)
            }
            session
        }
    }

    suspend fun restoreTabs(): String {
        val currentSettings = viewModelStateFlow
            .map { state -> state.logicSettings }
            .filterNotNull()
            .first()

        return browserTabController.restoreTabs(
            homepageUrl = currentSettings.homepageUrl,
        ).also { tabId ->
            if (browserTabController.selectedTabId != tabId) {
                browserTabController.selectTab(tabId)
            }
            // setupComplete は browserTabController.restoreTabs() 内で complete 済み
        }
    }

    suspend fun createTabWithHomepage(
        tabId: String,
        insertAfterSelectedTab: Boolean = true,
    ): BrowserTab {
        return browserTabController.createAndAppendTab(
            tabId = tabId,
            initialUrl = currentHomepageUrl(),
            insertAfterSelectedTab = insertAfterSelectedTab,
        )
    }

    // 外部タブを開く直前に選択されていたタブ ID を記憶するマップ
    private val externalTabPreviousTabs = mutableMapOf<String, String?>()
    private val externalTabCleanupMutex = Mutex()

    /**
     * 外部タブ登録時に呼ぶ。呼び出し時点の selectedTabId（= 外部タブ開封前のタブ）を記録する。
     * [selectTab] より前に呼び出すこと。
     */
    fun registerExternalTab(tabId: String) {
        externalTabPreviousTabs[tabId] = browserTabController.selectedTabId
    }

    fun isExternalTab(tabId: String): Boolean {
        return externalTabPreviousTabs.containsKey(tabId)
    }

    /**
     * 外部ダウンロードタブの確認ダイアログで確定/キャンセルされた後に呼ぶ。
     * タブを閉じ、デフォルトグループの最後のタブへ遷移するためのタブ ID を返す。
     */
    suspend fun finishExternalDownloadTab(tabId: String): String? {
        if (!externalTabPreviousTabs.containsKey(tabId)) {
            return null
        }
        externalTabPreviousTabs.remove(tabId)
        val defaultGroupId = tabGroupRepository.getDefaultGroupId()?.value
        val targetTabId = ExternalDownloadTabNavigationPolicy.resolveTargetTabAfterClosingExternalDownload(
            state = browserTabController.tabStoreState.value,
            defaultGroupId = defaultGroupId,
            excludingTabId = tabId,
        )
        browserTabController.closeTabWithUndo(tabId, nextSelectedTabId = targetTabId)
        browserTabController.confirmClosedTab()
        return targetTabId ?: browserTabController.selectedTabId
    }

    /**
     * 外部タブをバックで閉じる際に遷移すべきタブ ID を返す。
     * 外部タブを開く前のタブが存在すればそれを、なければ他の任意のタブを返す。
     * 他にタブが存在しない場合は null。
     */
    fun resolveBackTargetForExternalTab(tabId: String): String? {
        val previousTabId = externalTabPreviousTabs[tabId]
        return if (previousTabId != null && browserTabController.findTab(previousTabId) != null) {
            previousTabId
        } else {
            browserTabController.tabs.firstOrNull { it.tabId != tabId }?.tabId
        }
    }

    /**
     * Activity 終了時に永続化から外すべき外部タブ情報を返す。
     * システム既定の戻る処理を使うため、UI 側で BackHandler は消費しない。
     */
    fun snapshotSelectedExternalTabFinishCleanup(): ExternalTabFinishCleanup? {
        val selectedTabId = browserTabController.selectedTabId ?: return null
        if (!externalTabPreviousTabs.containsKey(selectedTabId)) {
            return null
        }
        return ExternalTabFinishCleanup(
            tabId = selectedTabId,
            nextSelectedTabId = resolveBackTargetForExternalTab(selectedTabId),
        )
    }

    /**
     * Activity が閉じる直前に外部タブだけを永続化から外す。
     * 先に保留中の保存を流し切ってから DB から削除し、次回起動時に復元されないようにする。
     */
    suspend fun cleanupSelectedExternalTabOnActivityFinishIfNeeded() {
        externalTabCleanupMutex.withLock {
            val cleanup = snapshotSelectedExternalTabFinishCleanup() ?: return
            externalTabPreviousTabs.remove(cleanup.tabId)
            browserTabController.awaitPersistenceIdle()
            withContext(Dispatchers.IO) {
                tabRepository.closeTab(cleanup.tabId, cleanup.nextSelectedTabId)
            }
        }
    }

    private fun currentHomepageUrl(): String {
        return viewModelStateFlow.value.logicSettings?.homepageUrl ?: "https://www.google.com"
    }

    override fun onCleared() {
        super.onCleared()
        browserTabController.close()
    }
}

private fun BrowserLogicSettings.toBrowserAppUiState(): BrowserAppUiState = BrowserAppUiState(
    themeMode = themeMode,
    homepageUrl = homepageUrl,
    searchTemplate = searchTemplate,
    translationProvider = translationProvider,
)

private fun ResolvedBrowserSettings.toLogicSettings(): BrowserLogicSettings = BrowserLogicSettings(
    themeMode = themeMode,
    homepageUrl = homepageUrl,
    searchTemplate = searchTemplate,
    translationProvider = translationProvider,
    enableThirdPartyCa = enableThirdPartyCa,
)

/** サイトごとの位置情報設定を拡張機能のモードへ変換する */
private fun SiteGeolocationState.toGeolocationMode(): MockLocationWebExtension.GeolocationMode {
    return when (this) {
        SiteGeolocationState.SITE_GEOLOCATION_DENY -> MockLocationWebExtension.GeolocationMode.DENY
        SiteGeolocationState.SITE_GEOLOCATION_REAL -> MockLocationWebExtension.GeolocationMode.REAL
        else -> MockLocationWebExtension.GeolocationMode.MOCK
    }
}
