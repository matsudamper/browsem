package net.matsudamper.browser

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
import net.matsudamper.browser.data.history.HistoryEntry
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
    private val settingsRepository = SettingsRepository(context)
    private val tabRepository = TabRepository(context)
    private val historyRepository = HistoryRepository(context)

    private val settings: StateFlow<BrowserSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val tabData: StateFlow<BrowserTabData?> = tabRepository.tabs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settingsUiState: StateFlow<SettingsUiState?> = settings
        .map { current -> current?.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var tabPersistenceSignal by mutableLongStateOf(0L)
        private set

    fun bumpTabPersistence() {
        tabPersistenceSignal++
    }

    suspend fun restoreTabs(): String {
        val currentSettings = settings.filterNotNull().first()
        val currentTabData = tabData.filterNotNull().first()
        val homepageUrl = currentSettings.resolvedHomepageUrl()
        val persistedTabs = currentTabData.tabStatesList.map { tabState ->
            PersistedBrowserTab(
                url = tabState.url,
                sessionState = tabState.sessionState,
                title = tabState.title,
                previewImageWebp = tabState.previewImageWebp.toByteArray(),
                tabId = tabState.tabId.ifBlank { java.util.UUID.randomUUID().toString() },
                openerTabId = tabState.openerTabId.ifBlank { null },
            )
        }
        val tabId = browserSessionController.restoreTabs(
            homepageUrl = homepageUrl,
            persistedTabs = persistedTabs,
            persistedSelectedTabIndex = currentTabData.selectedTabIndex,
        )
        return tabId
    }

    fun saveTabStates(selectedTabId: String?) {
        viewModelScope.launch {
            val tabs = browserSessionController.exportPersistedTabs()
            tabRepository.updateTabStates(
                tabs = tabs.map { tab ->
                    PersistedTabState(
                        url = tab.url,
                        sessionState = tab.sessionState,
                        title = tab.title,
                        previewImageWebp = ByteString.copyFrom(tab.previewImageWebp),
                        tabId = tab.tabId,
                        openerTabId = tab.openerTabId.orEmpty(),
                    )
                },
                selectedTabIndex = if (selectedTabId == null) {
                    null
                } else {
                    tabs.indexOfFirst { it.tabId == selectedTabId }
                        .takeIf { it >= 0 }
                } ?: tabs.lastIndex,
            )
        }
    }

    fun setHomepageType(type: HomepageType) {
        viewModelScope.launch {
            settingsRepository.setHomepageType(type)
        }
    }

    fun setCustomHomepageUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setCustomHomepageUrl(url)
        }
    }

    fun setSearchProvider(provider: SearchProvider) {
        viewModelScope.launch {
            settingsRepository.setSearchProvider(provider)
        }
    }

    fun setCustomSearchUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setCustomSearchUrl(url)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setTranslationProvider(provider: TranslationProvider) {
        viewModelScope.launch {
            settingsRepository.setTranslationProvider(provider)
        }
    }

    fun setEnableThirdPartyCa(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEnableThirdPartyCa(enabled)
        }
    }

    fun addNotificationAllowedOrigin(origin: String) {
        viewModelScope.launch {
            settingsRepository.addNotificationAllowedOrigin(origin)
        }
    }

    fun removeNotificationAllowedOrigin(origin: String) {
        viewModelScope.launch {
            settingsRepository.removeNotificationAllowedOrigin(origin)
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
                addNotificationAllowedOrigin(uri)
            }
            GeckoResult.fromValue(value)
        }
    }

    // --- 履歴 ---

    suspend fun recordHistory(url: String, title: String): Long {
        return historyRepository.recordVisit(url, title)
    }

    suspend fun updateHistoryTitle(id: Long, title: String) {
        historyRepository.updateTitle(id, title)
    }

    fun searchHistory(query: String): Flow<List<HistoryEntry>> {
        return if (query.isBlank()) {
            historyRepository.getRecent()
        } else {
            historyRepository.search(query)
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            historyRepository.deleteAll()
        }
    }

    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteById(id)
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
