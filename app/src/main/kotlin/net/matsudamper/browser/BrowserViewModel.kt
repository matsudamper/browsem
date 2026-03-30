package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

@Stable
internal class BrowserViewModel(
    val runtime: GeckoRuntime,
    val themeColorExtension: ThemeColorWebExtension,
    val mediaWebExtension: net.matsudamper.browser.media.MediaWebExtension,
    val readabilityWebExtension: ReadabilityWebExtension,
    private val settingsRepository: SettingsRepository,
    private val tabRepository: TabRepository,
    private val tabGroupRepository: TabGroupRepository,
    internal val historyRepository: net.matsudamper.browser.data.history.HistoryRepository,
) : ViewModel() {
    val browserTabController = BrowserTabController(tabRepository, tabGroupRepository)
    val browserSessionLifecycleController = BrowserSessionLifecycleController(runtime)

    // 構成変更を経ても破棄されないよう ViewModel で保持するセットアップ完了シグナル
    val setupComplete = CompletableDeferred<Unit>()

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
            setupComplete.complete(Unit)
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
        // 例外が発生した場合（別のリクエストが進行中など）は DENY を返す。
        // exception listener を指定しないと例外が伝播し GeckoView が JS Promise を解決しない恐れがある。
        return androidResult.then(
            { value ->
                if (value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
                    viewModelScope.launch { settingsRepository.addNotificationAllowedOrigin(uri) }
                }
                GeckoResult.fromValue(value)
            },
            { _ ->
                GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            },
        )
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

    /** タブを閉じ、即座に永続化する（外部URL タブをバックで閉じるときに使用）。 */
    suspend fun closeTabAndSaveImmediately(tabId: String) {
        val nextSelectedTabId = browserTabController.closeTab(tabId)
        // タブが空になった場合はホームタブを作成して空状態での保存を避ける
        if (nextSelectedTabId == null) {
            browserTabController.createAndAppendTab(initialUrl = currentHomepageUrl())
        }
        browserTabController.awaitPersistenceIdle()
        externalTabPreviousTabs.remove(tabId)
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
