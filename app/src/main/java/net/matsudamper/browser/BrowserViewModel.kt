package net.matsudamper.browser

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val appContext: android.content.Context,
    val runtime: GeckoRuntime,
    private val settingsRepository: SettingsRepository,
    private val tabRepository: TabRepository,
    internal val historyRepository: net.matsudamper.browser.data.history.HistoryRepository,
) : ViewModel() {
    private val runtimeCoordinator = BrowserRuntimeCoordinator(appContext, runtime)
    private val tabPersistenceCoordinator = TabPersistenceCoordinator(tabRepository)

    val browserSessionController: BrowserSessionController
        get() = runtimeCoordinator.browserSessionController
    val themeColorExtension: ThemeColorWebExtension
        get() = runtimeCoordinator.themeColorExtension
    val mediaWebExtension
        get() = runtimeCoordinator.mediaWebExtension

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
    notificationAllowedOrigins = notificationAllowedOriginsList,
    homepageUrl = resolvedHomepageUrl(),
    searchTemplate = resolvedSearchTemplate(),
)
