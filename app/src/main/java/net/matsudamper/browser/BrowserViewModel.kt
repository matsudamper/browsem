package net.matsudamper.browser

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.BrowserTabData
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.PersistedTabState
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.history.HistoryRepository
import net.matsudamper.browser.data.resolvedHomepageUrl
import net.matsudamper.browser.data.resolvedSearchTemplate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal data class SettingsUiState(
    val homepageType: HomepageType,
    val customHomepageUrl: String,
    val searchProvider: SearchProvider,
    val customSearchUrl: String,
    val themeMode: ThemeMode,
    val translationProvider: TranslationProvider,
    val enableThirdPartyCa: Boolean,
    val notificationAllowedOrigins: List<String>,
    val homepageUrl: String,
    val searchTemplate: String,
)

@Stable
internal class BrowserViewModel(
    val runtime: GeckoRuntime,
    context: Context,
) : ViewModel() {
    val browserSessionController = BrowserSessionController(runtime)
    val themeColorExtension = ThemeColorWebExtension().also { it.install(runtime) }
    internal val settingsRepository = SettingsRepository(context)
    private val tabRepository = TabRepository(context)
    internal val historyRepository = HistoryRepository(context)

    private val settings: StateFlow<BrowserSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val tabData: StateFlow<BrowserTabData?> = tabRepository.tabs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settingsUiState: StateFlow<SettingsUiState?> = settings
        .map { current -> current?.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 復元完了フラグ。これがtrueになるまで保存を行わない
    private var restorationComplete = false

    // 現在選択中のタブID（永続化用）
    var selectedTabId: String? by mutableStateOf(null)
        private set

    /**
     * 選択タブを更新する。
     * NavController での画面遷移とは別に、永続化のためにViewModelにも通知する。
     */
    fun selectTab(tabId: String) {
        selectedTabId = tabId
        browserSessionController.notifyStructuralChange()
    }

    suspend fun restoreTabs(): String {
        val currentSettings = settings.filterNotNull().first()
        val currentTabData = tabData.filterNotNull().first()
        val homepageUrl = currentSettings.resolvedHomepageUrl()
        val persistedTabs = currentTabData.tabStatesList.map { tabState ->
            val tabId = tabState.tabId.ifBlank { java.util.UUID.randomUUID().toString() }
            // キャッシュから読み込み、なければprotoの旧データを移行してキャッシュに保存する
            val thumbnail = tabRepository.loadTabThumbnail(tabId)
                ?: tabState.previewImageWebp.toByteArray().takeIf { it.isNotEmpty() }?.also { bytes ->
                    tabRepository.saveTabThumbnail(tabId, bytes)
                }
            PersistedBrowserTab(
                url = tabState.url,
                sessionState = tabState.sessionState,
                title = tabState.title,
                previewImageWebp = thumbnail ?: byteArrayOf(),
                tabId = tabId,
                openerTabId = tabState.openerTabId.ifBlank { null },
                themeColor = if (tabState.hasThemeColor()) tabState.themeColor else null,
            )
        }
        val tabId = browserSessionController.restoreTabs(
            homepageUrl = homepageUrl,
            persistedTabs = persistedTabs,
            persistedSelectedTabIndex = currentTabData.selectedTabIndex,
        )
        selectedTabId = tabId
        restorationComplete = true
        startTabPersistence()
        return tabId
    }

    /**
     * タブ状態の自動保存を開始する。
     * snapshotFlow で BrowserSessionController の変更を監視し、デバウンス付きで保存する。
     * restoreTabs() 完了後に呼ばれるため、復元前に空リストを保存するレースコンディションを防止する。
     */
    private fun startTabPersistence() {
        viewModelScope.launch {
            snapshotFlow {
                // コンテンツ変更（URL、タイトル、セッション状態、プレビュー画像）を自動追跡
                val content = browserSessionController.contentVersion
                // 構造変更（タブ追加・削除・並べ替え）と選択タブ変更を追跡
                val structural = browserSessionController.structuralVersion
                val selected = selectedTabId
                Triple(content, structural, selected)
            }.collectLatest {
                // デバウンス: 連続的な変更をまとめて1回の保存にする
                delay(500)
                saveTabStatesInternal()
            }
        }
    }

    private suspend fun saveTabStatesInternal() {
        if (!restorationComplete) return
        val tabs = browserSessionController.exportPersistedTabs()
        if (tabs.isEmpty()) {
            Log.w("BrowserViewModel", "タブリストが空のため保存をスキップ")
            return
        }
        val currentSelectedTabId = selectedTabId
        val selectedIndex = if (currentSelectedTabId != null) {
            tabs.indexOfFirst { it.tabId == currentSelectedTabId }
                .takeIf { it >= 0 }
        } else {
            null
        } ?: tabs.lastIndex

        // サムネイルをキャッシュファイルに保存
        val currentTabIds = tabs.map { it.tabId }.toSet()
        withContext(Dispatchers.IO) {
            tabs.forEach { tab ->
                if (tab.previewImageWebp.isNotEmpty()) {
                    tabRepository.saveTabThumbnail(tab.tabId, tab.previewImageWebp)
                }
            }
            // 削除されたタブのサムネイルファイルを削除
            tabRepository.deleteOrphanedThumbnails(currentTabIds)
        }

        tabRepository.updateTabStates(
            tabs = tabs.map { tab ->
                PersistedTabState(
                    url = tab.url,
                    sessionState = tab.sessionState,
                    title = tab.title,
                    tabId = tab.tabId,
                    openerTabId = tab.openerTabId.orEmpty(),
                    themeColor = tab.themeColor,
                )
            },
            selectedTabIndex = selectedIndex,
        )
    }

    fun handleNotificationPermission(
        uri: String,
        onDesktopNotificationPermissionRequest: () -> GeckoResult<Int>,
    ): GeckoResult<Int> {
        val allowedOrigins = settings.value?.notificationAllowedOriginsList ?: emptyList()
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

    fun applyRuntimeSettings() {
        runtime.settings.setEnterpriseRootsEnabled(settings.value?.enableThirdPartyCa ?: false)
    }

    override fun onCleared() {
        super.onCleared()
        browserSessionController.close()
    }
}

private fun BrowserSettings.toUiState(): SettingsUiState = SettingsUiState(
    homepageType = homepageType,
    customHomepageUrl = customHomepageUrl,
    searchProvider = searchProvider,
    customSearchUrl = customSearchUrl,
    themeMode = themeMode,
    translationProvider = translationProvider,
    enableThirdPartyCa = enableThirdPartyCa,
    notificationAllowedOrigins = notificationAllowedOriginsList,
    homepageUrl = resolvedHomepageUrl(),
    searchTemplate = resolvedSearchTemplate(),
)
