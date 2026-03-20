package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.BrowserSettings
import net.matsudamper.browser.data.HomepageType
import net.matsudamper.browser.data.SearchProvider
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.TabRepository
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.data.resolvedEnableWebSuggestions
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
    val enableWebSuggestions: Boolean,
    val notificationAllowedOrigins: List<String>,
    val homepageUrl: String,
    val searchTemplate: String,
)

@Stable
internal class BrowserViewModel(
    val runtime: GeckoRuntime,
    val themeColorExtension: ThemeColorWebExtension,
    val mediaWebExtension: net.matsudamper.browser.media.MediaWebExtension,
    val readabilityWebExtension: ReadabilityWebExtension,
    private val settingsRepository: SettingsRepository,
    private val tabRepository: TabRepository,
    internal val historyRepository: net.matsudamper.browser.data.history.HistoryRepository,
) : ViewModel() {
    private val runtimeCoordinator = BrowserRuntimeCoordinator(runtime, themeColorExtension, mediaWebExtension)
    private val tabPersistenceCoordinator = TabPersistenceCoordinator(tabRepository)

    val browserSessionController: BrowserSessionController
        get() = runtimeCoordinator.browserSessionController

    // 構成変更を経ても破棄されないよう ViewModel で保持するセットアップ完了シグナル
    val setupComplete = CompletableDeferred<Unit>()

    // プロセス存続中に onCreate で処理済みの Intent URL セット。
    // ViewModel はローテーション等の構成変更を経ても生存するが、プロセスキルで再生成される。
    // これを利用して「構成変更後の同 URL 再処理スキップ」を実現する。
    private val processedIntentUrls = mutableSetOf<String>()

    internal fun hasIntentUrlBeenProcessed(url: String): Boolean = url in processedIntentUrls

    internal fun markIntentUrlAsProcessed(url: String) {
        processedIntentUrls.add(url)
    }

    // ダウンロード画面を開く Intent についても同様に処理済みフラグを管理する
    private var downloadsIntentProcessed = false

    internal fun hasDownloadsIntentBeenProcessed(): Boolean = downloadsIntentProcessed

    internal fun markDownloadsIntentAsProcessed() {
        downloadsIntentProcessed = true
    }

    private val settings: StateFlow<BrowserSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settingsUiState: StateFlow<SettingsUiState?> = settings
        .map { current -> current?.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 選択タブを更新する。
     * NavController での画面遷移とは別に、タブ store にも通知する。
     */
    fun selectTab(tabId: String) {
        browserSessionController.selectTab(tabId)
    }

    suspend fun restoreTabs(): String {
        val currentSettings = settings.filterNotNull().first()
        val homepageUrl = currentSettings.resolvedHomepageUrl()

        return tabPersistenceCoordinator.restoreTabs(
            homepageUrl = homepageUrl,
            browserSessionController = browserSessionController,
        ).also { tabId ->
            browserSessionController.selectTab(tabId)
            tabPersistenceCoordinator.bind(
                scope = viewModelScope,
                browserSessionController = browserSessionController,
            )
            // setupComplete は呼び出し元（AppNavigation の Setup LaunchedEffect）で
            // selectTab() の後に complete する
        }
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

    /** タブを閉じ、即座に永続化する（外部URL タブをバックで閉じるときに使用）。 */
    suspend fun closeTabAndSaveImmediately(tabId: String, homepageUrl: String) {
        browserSessionController.closeTab(tabId)
        // タブが空になった場合はホームタブを作成して空状態での保存を避ける
        if (browserSessionController.tabs.isEmpty()) {
            browserSessionController.createAndAppendTab(initialUrl = homepageUrl)
        }
        tabPersistenceCoordinator.saveNow(browserSessionController)
    }

    fun applyRuntimeSettings() {
        runtimeCoordinator.applyRuntimeSettings(settings.value?.enableThirdPartyCa ?: false)
    }

    override fun onCleared() {
        super.onCleared()
        runtimeCoordinator.close()
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
    enableWebSuggestions = resolvedEnableWebSuggestions(),
    notificationAllowedOrigins = notificationAllowedOriginsList,
    homepageUrl = resolvedHomepageUrl(),
    searchTemplate = resolvedSearchTemplate(),
)
