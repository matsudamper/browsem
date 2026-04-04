package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.ResolvedBrowserSettings
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabGroupRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.resolvedBrowserSettings
import org.mozilla.geckoview.GeckoResult
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
    val notificationAllowedOrigins: Set<String>,
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
    val mediaWebExtension: net.matsudamper.browser.media.MediaWebExtension,
    private val settingsRepository: SettingsRepository,
    private val tabRepository: TabRepository,
    private val tabGroupRepository: TabGroupRepository,
) : ViewModel() {
    val browserTabController = BrowserTabController(
        tabRepository = tabRepository,
        tabGroupRepository = tabGroupRepository,
        isSinglePage = false,
    )
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    // タブ復元完了シグナル。BrowserTabController が内部で管理し、構成変更後も有効。
    val setupComplete: Deferred<Unit> get() = browserTabController.restoreComplete

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

    fun handleNotificationPermission(
        uri: String,
        onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
    ): GeckoResult<Int> {
        val allowedOrigins = viewModelStateFlow.value.logicSettings?.notificationAllowedOrigins ?: emptySet()
        if (allowedOrigins.contains(uri)) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
        }
        val androidResult = onDesktopNotificationPermissionRequest()
        return androidResult.then { value ->
            if (value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
                viewModelScope.launch { settingsRepository.addNotificationAllowedOrigin(uri) }
            }
            GeckoResult.fromValue(value)
        }
    }

    suspend fun createTabWithHomepage(
        tabId: String,
    ): BrowserTab {
        return browserTabController.createAndAppendTab(
            tabId = tabId,
            initialUrl = currentHomepageUrl(),
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
    notificationAllowedOrigins = notificationAllowedOrigins.toSet(),
)
